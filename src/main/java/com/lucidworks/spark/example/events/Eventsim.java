package com.lucidworks.spark.example.events;

import com.lucidworks.spark.SparkApp;
import com.lucidworks.spark.fusion.FusionPipelineClient;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.solr.common.util.DateUtil;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;
import org.apache.spark.sql.types.StructType;

import java.util.*;

/**
 * Index data generated by eventsim into Fusion using an indexing pipeline.
 */
public class Eventsim implements SparkApp.RDDProcessor {

  public static final String DEFAULT_ENDPOINT =
          "http://localhost:8764/api/apollo/index-pipelines/eventsim-default/collections/eventsim/index";

  public String getName() {
    return "eventsim";
  }

  public Option[] getOptions() {
    return new Option[]{
      OptionBuilder
              .withArgName("FILE")
              .hasArg()
              .isRequired(true)
              .withDescription("Path to an eventsim JSON file")
              .create("eventsimJson"),
      OptionBuilder
              .withArgName("URL(s)")
              .hasArg()
              .isRequired(false)
              .withDescription("Fusion endpoint(s); default is "+DEFAULT_ENDPOINT)
              .create("fusion"),
      OptionBuilder
              .withArgName("USERNAME")
              .hasArg()
              .isRequired(false)
              .withDescription("Fusion username; default is admin")
              .create("fusionUser"),
      OptionBuilder
              .withArgName("PASSWORD")
              .hasArg()
              .isRequired(false)
              .withDescription("Fusion password; required if fusionAuthEnbled=true")
              .create("fusionPass"),
      OptionBuilder
              .withArgName("REALM")
              .hasArg()
              .isRequired(false)
              .withDescription("Fusion security realm; default is native")
              .create("fusionRealm"),
      OptionBuilder
              .withArgName("true|false")
              .hasArg()
              .isRequired(false)
              .withDescription("Fusion authentication enabled; default is true")
              .create("fusionAuthEnabled"),
      OptionBuilder
              .withArgName("INT")
              .hasArg()
              .isRequired(false)
              .withDescription("Fusion indexing batch size; default is 100")
              .create("fusionBatchSize")
    };
  }

  public int run(SparkConf conf, CommandLine cli) throws Exception {

    final String fusionEndpoints = cli.getOptionValue("fusion", DEFAULT_ENDPOINT);
    final boolean fusionAuthEnabled = "true".equalsIgnoreCase(cli.getOptionValue("fusionAuthEnabled", "true"));
    final String fusionUser = cli.getOptionValue("fusionUser", "admin");

    final String fusionPass = cli.getOptionValue("fusionPass");
    if (fusionAuthEnabled && (fusionPass == null || fusionPass.isEmpty()))
      throw new IllegalArgumentException("Fusion password is required when authentication is enabled!");

    final String fusionRealm = cli.getOptionValue("fusionRealm", "native");
    final int fusionBatchSize = Integer.parseInt(cli.getOptionValue("fusionBatchSize", "100"));

    JavaSparkContext jsc = new JavaSparkContext(conf);
    SQLContext sqlContext = new HiveContext(jsc.sc());

    // load up eventsim JSON
    DataFrame eventsim = sqlContext.read().json(cli.getOptionValue("eventsimJson"));

    // send to a Fusion indexing pipeline
    eventsim.toJavaRDD().foreachPartition(new VoidFunction<Iterator<Row>>() {
      public void call(Iterator<Row> rowIterator) throws Exception {
        FusionPipelineClient fusion = fusionAuthEnabled ?
                new FusionPipelineClient(fusionEndpoints, fusionUser, fusionPass, fusionRealm) :
                new FusionPipelineClient(fusionEndpoints);

        List batch = new ArrayList(fusionBatchSize);
        while (rowIterator.hasNext()) {
          Row next = rowIterator.next();
          StructType schema = next.schema();
          List fields = new ArrayList();

          String userId = null;
          String sessionId = null;
          Long ts = null;

          for (int c = 0; c < next.length(); c++) {
            Object val = next.get(c);
            if (val == null)
              continue;

            String fieldName = schema.fieldNames()[c];
            if ("ts".equals(fieldName)) {
              // convert the timestamp into an ISO date
              ts = (Long) val;
              Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
              cal.setTimeInMillis(ts);
              val = DateUtil.getThreadLocalDateFormat().format(cal.getTime());
            } else if ("userId".equals(fieldName)) {
              userId = val.toString();
            } else if ("sessionId".equals(fieldName)) {
              sessionId = val.toString();
            }

            Map fieldMap = new HashMap(5);
            fieldMap.put("name", fieldName);
            fieldMap.put("value", val);
            fields.add(fieldMap);
          }

          if (fields.isEmpty())
            continue;

          if (userId == null || sessionId == null || ts == null)
            continue;

          Map rowMap = new HashMap();
          rowMap.put("id", String.format("%s-%s-%d", userId, sessionId, ts));
          rowMap.put("fields", fields);
          batch.add(rowMap);

          if (batch.size() == fusionBatchSize) {
            fusion.postBatchToPipeline(batch);
            batch.clear();
          }
        }

        if (!batch.isEmpty()) {
          fusion.postBatchToPipeline(batch);
          batch.clear();
        }
      }
    });

    jsc.stop();

    return 0;
  }
}
