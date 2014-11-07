/*
 * Apache HTTPD logparsing made easy
 * Copyright (C) 2013 Niels Basjes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package nl.basjes.hadoop.input;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nl.basjes.parse.apachehttpdlog.ApacheHttpdLoglineParser;
import nl.basjes.parse.core.Casts;
import nl.basjes.parse.core.Parser;
import nl.basjes.parse.core.exceptions.DisectionFailure;
import nl.basjes.parse.core.exceptions.InvalidDisectorException;
import nl.basjes.parse.core.exceptions.MissingDisectorsException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang.StringUtils.join;

@SuppressWarnings({ "PMD.OnlyOneReturn", "PMD.BeanMembersShouldSerialize" })
public class ApacheHttpdLogfileRecordReader extends
        RecordReader<LongWritable, MapWritable> {

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

    // --------------------------------------------

    @SuppressWarnings("unused") // Used by the Hadoop framework
    public ApacheHttpdLogfileRecordReader() {
        // Nothing to do here
    }

    public ApacheHttpdLogfileRecordReader(String newLogformat,
            Set<String> newRequestedFields) {
        setLogFormat(newLogformat);
        addRequestedFields(newRequestedFields);
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

    private boolean outputAllPossibleFields = false;
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

        if (logformat != null && fieldList != null && parser == null) {
            parser = createParser();
        }
        setupFields();
    }

    protected Parser<ParsedRecord> instantiateParser(String logFormat) throws ParseException {
        return new ApacheHttpdLoglineParser<>(ParsedRecord.class, logformat);
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
                allCasts = newParser.getAllCasts();
            }
        } catch (MissingDisectorsException | InvalidDisectorException | NoSuchMethodException | IOException | ParseException e) {
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
            // FIXME: So far I do not see a way to do this more efficiently yet
            newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                    String.class, String.class), fieldList);
            newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                    String.class, Long.class), fieldList);
            newParser.addParseTarget(ParsedRecord.class.getMethod("set",
                    String.class, Double.class), fieldList);
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
                } catch (DisectionFailure e) {
                    counterBadLines.increment(1L);
                    if (errorLinesLogged < MAX_ERROR_LINES_LOGGED) {
                        LOG.error("Parse error >>>{}<<< in line: >>>{}<<<", e.getMessage(), inputLine);
                        errorLinesLogged++;
                        if (errorLinesLogged == MAX_ERROR_LINES_LOGGED) {
                            LOG.error(">>>>>>>>>>> We now stop logging parse errors! <<<<<<<<<<<");
                        }
                    }
                    // Ignore bad lines and simply continue
                } catch (InvalidDisectorException e) {
                    LOG.error("InvalidDisectorException >>>{}<<<", e.getMessage());
                    return false;
                } catch (MissingDisectorsException e) {
                    LOG.error("MissingDisectorsException >>>{}<<<", e.getMessage());
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
    public MapWritable getCurrentValue() {
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
