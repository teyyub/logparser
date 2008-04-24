/*
 * Apache HTTPD logparsing made easy
 * Copyright (C) 2011-2015 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.basjes.hadoop.input;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nl.basjes.parse.httpdlog.ApacheHttpdLoglineParser;
import nl.basjes.parse.core.Casts;
import nl.basjes.parse.core.Dissector;
import nl.basjes.parse.core.Parser;
import nl.basjes.parse.core.exceptions.DissectionFailure;
import nl.basjes.parse.core.exceptions.InvalidDissectorException;
import nl.basjes.parse.core.exceptions.MissingDissectorsException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({ "PMD.OnlyOneReturn", "PMD.BeanMembersShouldSerialize" })
public class ApacheHttpdLogfileRecordReader extends
        RecordReader<LongWritable, ParsedRecord> {

    private static final Logger LOG = LoggerFactory.getLogger(ApacheHttpdLogfileRecordReader.class);

    private static final String APACHE_HTTPD_LOGFILE_INPUT_FORMAT = "Apache HTTPD Logfile InputFormat";
    public static final String FIELDS = "fields";

    // --------------------------------------------

    private final LineRecordReader                 lineReader      = new LineRecordReader();
    private Parser<ParsedRecord>                   parser;
    private List<String> fieldList = null;

    private final ParsedRecord                     currentValue    = new ParsedRecord();

    private String                                 logformat       = null;
    private final Set<String>                      requestedFields = new HashSet<>();
    private Map<String, Set<String>>               typeRemappings  = new HashMap<>(16);
    private List<Dissector>                        additionalDissectors;

    // --------------------------------------------

    @SuppressWarnings("unused") // Used by the Hadoop framework
    public ApacheHttpdLogfileRecordReader() {
        // Nothing to do here
    }

    public ApacheHttpdLogfileRecordReader(String logformat,
            Set<String> requestedFields,
            Map<String, Set<String>> typeRemappings,
            List<Dissector> additionalDissectors) {
        setLogFormat(logformat);
        // Mappings and additional parsers MUST come before the requested fields
        this.typeRemappings = typeRemappings;
        this.additionalDissectors = additionalDissectors;
        addRequestedFields(requestedFields);
    }

    private void addRequestedFields(Set<String> newRequestedFields) {
        requestedFields.addAll(newRequestedFields);
        fieldList = new ArrayList<>(requestedFields);
        setupFields();
    }

    private void setLogFormat(String newLogformat) {
        if (newLogformat == null) {
            return;
        }
        logformat = newLogformat;
    }

    private boolean                         outputAllPossibleFields = false;
    private String                          allPossiblePathsFieldName;
    private List<String>                    allPossiblePaths = null;

    private Counter counterLinesRead;
    private Counter counterGoodLines;
    private Counter counterBadLines;

    @Override
    public void initialize(final InputSplit split,
            final TaskAttemptContext context) throws IOException {
        lineReader.initialize(split, context);
        final Configuration conf = context.getConfiguration();

        counterLinesRead = context.getCounter(APACHE_HTTPD_LOGFILE_INPUT_FORMAT, "1:Lines read");
        counterGoodLines = context.getCounter(APACHE_HTTPD_LOGFILE_INPUT_FORMAT, "2:Good lines");
        counterBadLines  = context.getCounter(APACHE_HTTPD_LOGFILE_INPUT_FORMAT, "3:Bad lines");

        if (logformat == null || requestedFields.isEmpty()) {
            if (logformat == null) {
                logformat = conf.get("nl.basjes.parse.apachehttpdlogline.format", "common");
            }
            if (requestedFields.isEmpty()) {
                String fields = conf.get(
                        "nl.basjes.parse.apachehttpdlogline.fields", null);

                if (fields != null) {
                    fieldList = Arrays.asList(fields.split(","));
                }
            } else {
                fieldList = new ArrayList<>(requestedFields);
            }
        }

        if (fieldList != null) {
            if (logformat != null && parser == null) {
                parser = createParser();
            }
            for (String field : fieldList) {
                currentValue.declareRequestedFieldname(field);
            }
        }

        setupFields();
    }

    protected Parser<ParsedRecord> instantiateParser(String logFormat) throws ParseException {
        ApacheHttpdLoglineParser<ParsedRecord> newParser = new ApacheHttpdLoglineParser<>(ParsedRecord.class, logformat);
        newParser.setTypeRemappings(typeRemappings);
        newParser.addDissectors(additionalDissectors);
        return newParser;
    }

    private Map<String, EnumSet<Casts>> allCasts;
    private void setupFields() {
        try {
            String firstField = fieldList.get(0);
            if (fieldList.size() == 1 &&
                firstField.toLowerCase().trim().equals(FIELDS)) {
                outputAllPossibleFields = true;
                allPossiblePaths = getParser().getPossiblePaths();
                allPossiblePathsFieldName = firstField;
                Parser<ParsedRecord> newParser = instantiateParser(logformat);
                newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                        String.class, String.class), allPossiblePaths);
                newParser.addTypeRemappings(typeRemappings);
                allCasts = newParser.getAllCasts();
            }
        } catch (MissingDissectorsException | InvalidDissectorException | NoSuchMethodException | IOException | ParseException e) {
            e.printStackTrace();
        }
    }

    public EnumSet<Casts> getCasts(String name) throws IOException {
        if (outputAllPossibleFields) {
            return allCasts.get(name);
        }
        return getParser().getCasts(name);
    }

    public Parser<ParsedRecord> getParser() throws IOException {
        if (parser == null) {
            parser = createParser();
        }
        return parser;
    }

    private Parser<ParsedRecord> createParser() throws IOException {
        if (fieldList == null || logformat == null) {
            return null;
        }

        Parser<ParsedRecord> newParser;
        try {
            newParser = instantiateParser(logformat);

            for (String field: fieldList) {
                if (field.endsWith(".*")) {
                    newParser.addParseTarget(ParsedRecord.class.getMethod("setMultiValueString",
                            String.class, String.class), field);
                } else {
                    // FIXME: So far I do not see a way to do this more efficiently yet
                    newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                            String.class, String.class), field);
                    newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                            String.class, Long.class), field);
                    newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                            String.class, Double.class), field);
                }
            }

        } catch (ParseException
                |NoSuchMethodException
                |SecurityException e) {
            throw new IOException(e.toString());
        }
        return newParser;
    }

    // --------------------------------------------

    private int errorLinesLogged = 0;
    private static final int MAX_ERROR_LINES_LOGGED = 10;

    @Override
    public boolean nextKeyValue() throws IOException {
        if (outputAllPossibleFields) {
            // We now ONLY return the possible names of the fields that can be requested
            if (allPossiblePaths.isEmpty()) {
                return false;
            }

            currentValue.clear();

            String value = allPossiblePaths.get(0);
            allPossiblePaths.remove(0);
            currentValue.set(allPossiblePathsFieldName, value);
            return true;
        } else {
            boolean haveValue = false;
            while (!haveValue) {
                if (!lineReader.nextKeyValue()) {
                    return false;
                }

                counterLinesRead.increment(1L);

                currentValue.clear();
                String inputLine = lineReader.getCurrentValue().toString();
                try {
                    getParser().parse(currentValue, lineReader.getCurrentValue().toString());
                    counterGoodLines.increment(1L);
                    haveValue = true;
                } catch (DissectionFailure e) {
                    counterBadLines.increment(1L);
                    if (errorLinesLogged < MAX_ERROR_LINES_LOGGED) {
                        LOG.error("Parse error >>>{}<<< in line: >>>{}<<<", e.getMessage(), inputLine);
                        errorLinesLogged++;
                        if (errorLinesLogged == MAX_ERROR_LINES_LOGGED) {
                            LOG.error(">>>>>>>>>>> We now stop logging parse errors! <<<<<<<<<<<");
                        }
                    }
                    // Ignore bad lines and simply continue
                } catch (InvalidDissectorException e) {
                    LOG.error("InvalidDissectorException >>>{}<<<", e.getMessage());
                    return false;
                } catch (MissingDissectorsException e) {
                    LOG.error("MissingDissectorsException >>>{}<<<", e.getMessage());
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public LongWritable getCurrentKey() throws IOException,
            InterruptedException {
        // The key we return is the same byte offset as the TextInputFormat
        // would give.
        return lineReader.getCurrentKey();
    }

    @Override
    public ParsedRecord getCurrentValue() {
        return currentValue;
    }

    @Override
    public float getProgress() throws IOException {
        return lineReader.getProgress();
    }

    // --------------------------------------------

    @Override
    public void close() throws IOException {
        lineReader.close();
    }

    // --------------------------------------------
}