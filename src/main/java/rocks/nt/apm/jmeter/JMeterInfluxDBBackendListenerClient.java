package rocks.nt.apm.jmeter;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterContextService.ThreadCounts;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.influxdb.BatchOptions;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Point.Builder;
import rocks.nt.apm.jmeter.config.influxdb.InfluxDBConfig;
import rocks.nt.apm.jmeter.config.influxdb.RequestMeasurement;
import rocks.nt.apm.jmeter.config.influxdb.TestStartEndMeasurement;
import rocks.nt.apm.jmeter.config.influxdb.VirtualUsersMeasurement;



/**
 * Backend listener that writes JMeter metrics to influxDB directly.
 * 
 * @author Alexander Wert
 *
 */
public class JMeterInfluxDBBackendListenerClient extends AbstractBackendListenerClient implements Runnable {
  /**
   * Logger.
   */
  private static final Logger LOGGER = LoggingManager.getLoggerForClass();

  /**
   * Parameter Keys.
   */
  private static final String KEY_USE_REGEX_FOR_SAMPLER_LIST = "useRegexForSamplerList";
  private static final String KEY_TEST_NAME = "testName";
  private static final String KEY_SCENARIO_NAME = "scenarioName";
  private static final String KEY_NODE_NAME = "nodeName";
  private static final String KEY_SAMPLERS_LIST = "samplersList";
  private static final String KEY_LOG_ERROR_DETAILS = "logErrorDetails";

  /**
   * Constants.
   */
  private static final String SEPARATOR = ";";
  private static final int ONE_MS_IN_NANOSECONDS = 1000000;

  /**
   * Scheduler for periodic metric aggregation.
   */
  private ScheduledExecutorService scheduler;

  /**
   * Name of the test.
   */
  private String testName;

  /**
   * Name of the name
   */
  private String nodeName;

  /**
   * Name of the test scenario
   */
  private String scenarioName;

  /**
   * List of samplers to record.
   */
  private String samplersList = "";

  /**
   * Regex if samplers are defined through regular expression.
   */
  private String regexForSamplerList;

  /**
   * Set of samplers to record.
   */
  private Set<String> samplersToFilter;

  /**
   * InfluxDB configuration.
   */
  InfluxDBConfig influxDBConfig;

  /**
   * influxDB client.
   */
  private InfluxDB influxDB;

  /**
   * Random number generator
   */
  private Random randomNumberGenerator;
  
  /**
   * Current epoch time in seconds
   */
  private long currentEpochTimeInSeconds;

  /**
   * Processes sampler results.
   */
  public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
    boolean logErrorDetails = context.getBooleanParameter(KEY_LOG_ERROR_DETAILS, true);

    for (SampleResult sampleResult : sampleResults) {
      getUserMetrics().add(sampleResult);

      if ((null != regexForSamplerList && sampleResult.getSampleLabel().matches(regexForSamplerList)) || samplersToFilter.contains(sampleResult.getSampleLabel())) {
        String assertionMsg = "";
        String responseMsg = sampleResult.getResponseMessage();
        String requestData = "";
        String responseData = "";
        String requestHeaders = "";
        String responseHeaders = "";

        if (logErrorDetails) {
          if (!sampleResult.isSuccessful()) {
            AssertionResult[] assertionResults = sampleResult.getAssertionResults();

            requestData = sampleResult.getSamplerData();
            responseData = sampleResult.getResponseDataAsString();
            requestHeaders = sampleResult.getRequestHeaders();
            responseHeaders = sampleResult.getResponseHeaders();

            if (requestData == null) {
              requestData = "requestData is null";
            }

            if (responseData == null) {
              responseData = "responseData is null";
            }

            if (requestHeaders == null) {
              requestHeaders = "requestHeaders is null";
            }

            if (responseHeaders == null) {
              responseHeaders = "responseHeaders is null";
            }

            for (int i = 0; i < assertionResults.length; ++i) {
              AssertionResult ar = assertionResults[i];
              if (ar.isError() || ar.isFailure()) {
                assertionMsg += ar.getFailureMessage();
              }
            } 
          }
        }

        Point point = Point.measurement(RequestMeasurement.MEASUREMENT_NAME).time(sampleResult.getStartTime() * ONE_MS_IN_NANOSECONDS + getUniqueNumberForTheSamplerThread(), TimeUnit.NANOSECONDS)
            .tag(RequestMeasurement.Tags.REQUEST_NAME, sampleResult.getSampleLabel())
            .addField(RequestMeasurement.Fields.ERROR_COUNT, sampleResult.getErrorCount())
            .tag(RequestMeasurement.Fields.TEST_NAME, testName)
            .tag(RequestMeasurement.Tags.SCENARIO_NAME, scenarioName)
            .tag(RequestMeasurement.Fields.NODE_NAME, nodeName)
            .tag(RequestMeasurement.Tags.RESPONSE_CODE, sampleResult.getResponseCode())
            .tag(RequestMeasurement.Tags.RESPONSE_MESSAGE, responseMsg.equals("") ? "NULL" : responseMsg)
            .tag(RequestMeasurement.Tags.FAILURE_MESSAGE, assertionMsg.equals("") ? "NULL" : assertionMsg)
            .addField(RequestMeasurement.Fields.THREAD_NAME, sampleResult.getThreadName())
            .addField(RequestMeasurement.Fields.RESPONSE_TIME, sampleResult.getTime())
            .addField(RequestMeasurement.Fields.REQUEST_DATA, requestData)
            .addField(RequestMeasurement.Fields.RESPONSE_DATA, responseData)
            .addField(RequestMeasurement.Fields.REQUEST_HEADERS, requestHeaders)
            .addField(RequestMeasurement.Fields.RESPONSE_HEADERS, responseHeaders)
            .build();
        influxDB.write(influxDBConfig.getInfluxDatabase(), influxDBConfig.getInfluxRetentionPolicy(), point);
      }
    }
  }

  @Override
  public Arguments getDefaultParameters() {
    Arguments arguments = new Arguments();
    arguments.addArgument(KEY_TEST_NAME, "Test");
    arguments.addArgument(KEY_SCENARIO_NAME, "Scenario");
    arguments.addArgument(KEY_NODE_NAME, "Test-Node");
    arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_HOST, "localhost");
    arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PORT, Integer.toString(InfluxDBConfig.DEFAULT_PORT));
    arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_USER, "jmeter");
    arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_PASSWORD, "");
    arguments.addArgument(InfluxDBConfig.KEY_INFLUX_DB_DATABASE, InfluxDBConfig.DEFAULT_DATABASE);
    arguments.addArgument(InfluxDBConfig.KEY_RETENTION_POLICY, InfluxDBConfig.DEFAULT_RETENTION_POLICY);
    arguments.addArgument(KEY_SAMPLERS_LIST, ".*");
    arguments.addArgument(KEY_USE_REGEX_FOR_SAMPLER_LIST, "true");
    arguments.addArgument(KEY_LOG_ERROR_DETAILS, "true");
    return arguments;
  }

  @Override
  public void setupTest(BackendListenerContext context) throws Exception {
    testName = context.getParameter(KEY_TEST_NAME, "Test");
    scenarioName = context.getParameter(KEY_SCENARIO_NAME, "Scenario");
    nodeName = context.getParameter(KEY_NODE_NAME, "Test-Node");
    randomNumberGenerator = new Random();

    setupInfluxClient(context);
    influxDB.write(
        influxDBConfig.getInfluxDatabase(),
        influxDBConfig.getInfluxRetentionPolicy(),
        Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.STARTED)
            .tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
            .tag(TestStartEndMeasurement.Tags.SCENARIO_NAME, scenarioName)
            .addField(TestStartEndMeasurement.Fields.TEST_NAME, testName)
            .build());

    parseSamplers(context);
    scheduler = Executors.newScheduledThreadPool(1);

    scheduler.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public void teardownTest(BackendListenerContext context) throws Exception {
    LOGGER.info("Shutting down influxDB scheduler...");
    scheduler.shutdown();

    int noOfTotalThreads = JMeterContextService.getTotalThreads();

    addVirtualUsersMetrics(0,0,0,0,noOfTotalThreads);
    influxDB.write(
        influxDBConfig.getInfluxDatabase(),
        influxDBConfig.getInfluxRetentionPolicy(),
        Point.measurement(TestStartEndMeasurement.MEASUREMENT_NAME).time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .tag(TestStartEndMeasurement.Tags.TYPE, TestStartEndMeasurement.Values.FINISHED)
            .tag(TestStartEndMeasurement.Tags.NODE_NAME, nodeName)
            .tag(TestStartEndMeasurement.Tags.SCENARIO_NAME, scenarioName)
            .addField(TestStartEndMeasurement.Fields.TEST_NAME, testName)
            .build());

    influxDB.disableBatch();
    try {
      scheduler.awaitTermination(30, TimeUnit.SECONDS);
      LOGGER.info("influxDB scheduler terminated!");
    } catch (InterruptedException e) {
      LOGGER.error("Error waiting for end of scheduler");
    }

    samplersToFilter.clear();
    super.teardownTest(context);
  }

  /**
   * Periodically writes virtual users metrics to influxDB.
   */
  @Override
  public void run() {
    try {
      int noOfThreads = JMeterContextService.getNumberOfThreads();
      int noOfTotalThreads = JMeterContextService.getTotalThreads();

      ThreadCounts tc = JMeterContextService.getThreadCounts();
      addVirtualUsersMetrics(getUserMetrics().getMinActiveThreads(), getUserMetrics().getMeanActiveThreads(), getUserMetrics().getMaxActiveThreads(), noOfThreads, noOfTotalThreads - noOfThreads);
    } catch (Exception e) {
      LOGGER.error("Failed writing to influx", e);
    }
  }

  /**
   * Setup influxDB client.
   * 
   * @param context
   *            {@link BackendListenerContext}.
   */
  private void setupInfluxClient(BackendListenerContext context) {
    influxDBConfig = new InfluxDBConfig(context);
    influxDB = InfluxDBFactory.connect(influxDBConfig.getInfluxDBURL(), influxDBConfig.getInfluxUser(), influxDBConfig.getInfluxPassword());
    influxDB.setRetentionPolicy("defaultPolicy");

    influxDB.enableBatch(
      BatchOptions.DEFAULTS.
        actions(1000).
        flushDuration(5000).
        jitterDuration(1000).
        bufferLimit(50000).
        exceptionHandler(
          (failedPoints, throwable) -> {
            /* custom error handling for async batch sending process */
            LOGGER.error("Async batch write failed for points, it will be retried later"); 
          }
        )
    );
    createDatabaseIfNotExistent();
  }

  /**
   * Parses list of samplers.
   * 
   * @param context
   *            {@link BackendListenerContext}.
   */
  private void parseSamplers(BackendListenerContext context) {
    samplersList = context.getParameter(KEY_SAMPLERS_LIST, "");
    samplersToFilter = new HashSet<String>();
    if (context.getBooleanParameter(KEY_USE_REGEX_FOR_SAMPLER_LIST, false)) {
      regexForSamplerList = samplersList;
    } else {
      regexForSamplerList = null;
      String[] samplers = samplersList.split(SEPARATOR);
      samplersToFilter = new HashSet<String>();
      for (String samplerName : samplers) {
        samplersToFilter.add(samplerName);
      }
    }
  }

  /**
   * Write thread metrics.
   */
  private void addVirtualUsersMetrics(int minActiveThreads, int meanActiveThreads, int maxActiveThreads, int startedThreads, int finishedThreads) {
    if (currentEpochTimeInSeconds == 0) {
      currentEpochTimeInSeconds = System.currentTimeMillis() / 1000L;
    } else {
      currentEpochTimeInSeconds++;
    }

    Builder builder = Point.measurement(VirtualUsersMeasurement.MEASUREMENT_NAME).time(currentEpochTimeInSeconds, TimeUnit.SECONDS);
    builder.addField(VirtualUsersMeasurement.Fields.MIN_ACTIVE_THREADS, minActiveThreads);
    builder.addField(VirtualUsersMeasurement.Fields.MAX_ACTIVE_THREADS, maxActiveThreads);
    builder.addField(VirtualUsersMeasurement.Fields.MEAN_ACTIVE_THREADS, meanActiveThreads);
    builder.addField(VirtualUsersMeasurement.Fields.STARTED_THREADS, startedThreads);
    builder.addField(VirtualUsersMeasurement.Fields.FINISHED_THREADS, finishedThreads);
    builder.tag(VirtualUsersMeasurement.Tags.NODE_NAME, nodeName);
    builder.tag(VirtualUsersMeasurement.Tags.SCENARIO_NAME, scenarioName);
    influxDB.write(influxDBConfig.getInfluxDatabase(), influxDBConfig.getInfluxRetentionPolicy(), builder.build());
  }

  /**
   * Creates the configured database in influx if it does not exist yet.
   */
  private void createDatabaseIfNotExistent() {
    List<String> dbNames = influxDB.describeDatabases();
    if (!dbNames.contains(influxDBConfig.getInfluxDatabase())) {
      influxDB.createDatabase(influxDBConfig.getInfluxDatabase());
    }
  }

  /**
   * Try to get a unique number for the sampler thread
   */
  private int getUniqueNumberForTheSamplerThread() {
    return randomNumberGenerator.nextInt(ONE_MS_IN_NANOSECONDS);
  }
}
