import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

public class BowtieJsonSchemaValidator {

  private static final JsonSchemaFactory factory =
      JsonSchemaFactory.getInstance();

  private static final List<String> DIALECTS =
      List.of("https://json-schema.org/draft/2020-12/schema",
              "https://json-schema.org/draft/2019-09/schema",
              "http://json-schema.org/draft-07/schema#",
              "http://json-schema.org/draft-06/schema#",
              "http://json-schema.org/draft-04/schema#");

  private String dialect;

  private final ObjectMapper objectMapper = new ObjectMapper().configure(
      DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final PrintStream output;
  private boolean started = false;

  public static void main(String[] args) {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in));
    new BowtieJsonSchemaValidator(System.out).run(reader);
  }

  public BowtieJsonSchemaValidator(PrintStream output) { this.output = output; }

  private void run(BufferedReader reader) {
    reader.lines().forEach(this::handle);
  }

  private void handle(String data) {
    try {
      JsonNode node = objectMapper.readTree(data);
      String cmd = node.get("cmd").asText();
      switch (cmd) {
        case "start" -> start(node);
        case "dialect" -> dialect(node);
        case "run" -> run(node);
        case "stop" -> System.exit(0);
        default -> throw new IllegalArgumentException(
          "Unknown cmd [%s]".formatted(cmd)
        );
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void start(JsonNode node) throws IOException {
    started = true;
    StartRequest startRequest = objectMapper.treeToValue(
      node,
      StartRequest.class
    );
    if (startRequest.version() != 1) {
      throw new IllegalArgumentException(
        "Unsupported IHOP version [%d]".formatted(startRequest.version())
      );
    }

    InputStream is = getClass().getResourceAsStream("META-INF/MANIFEST.MF");
    var attributes = new Manifest(is).getMainAttributes();

    StartResponse startResponse = new StartResponse(
      1,
      true,
      new Implementation(
        "java",
        attributes.getValue("Implementation-Name"),
        attributes.getValue("Implementation-Version"),
        DIALECTS,
        "https://github.com/networknt/json-schema-validator/",
        "https://github.com/networknt/json-schema-validator/issues",
        System.getProperty("os.name"),
        System.getProperty("os.version"),
        System.getProperty("java.vendor.version")
      )
    );
    output.println(objectMapper.writeValueAsString(startResponse));
  }

  private void dialect(JsonNode node) throws JsonProcessingException {
    DialectRequest dialectRequest = objectMapper.treeToValue(
      node,
      DialectRequest.class
    );

    if (!started) {
      throw new RuntimeException("Not started!");
    }

    dialect = dialectRequest.dialect();
    if (dialect == null) {
      throw new RuntimeException("Bad dialect!");
    }
    DialectResponse dialectResponse = new DialectResponse(true);
    output.println(objectMapper.writeValueAsString(dialectResponse));
  }

  private void run(JsonNode node)
    throws JsonProcessingException, IllegalArgumentException {
    if (!started) {
      throw new RuntimeException("Not started!");
    }

    RunRequest runRequest = objectMapper.treeToValue(node, RunRequest.class);

    try {
      TestCase testCase = runRequest.testCase();

      JsonNode schema = testCase.schema();

      List<TestResult> results = new ArrayList<>();
      for (Test test : testCase.tests()) {
            JsonSchema jsonSchema = factory.getSchema(schema);
            Set<ValidationMessage> errors =
                jsonSchema.validate(test.instance());
            boolean valid = (errors == null || errors.size() == 0);
            results.add(new TestResult(valid));
          }

          RunResponse runResponse = new RunResponse(runRequest.seq(), results);
          output.println(objectMapper.writeValueAsString(runResponse));
        }
        catch (Exception e) {
          String stackTrace = stackTraceToString(e);
          RunErroredResponse erroredResponse = new RunErroredResponse(
              runRequest.seq(), true,
              new ErrorContext(e.getMessage(), stackTrace));
          output.println(objectMapper.writeValueAsString(erroredResponse));
        }
    }

    private String stackTraceToString(Exception e) {
        StringWriter stringWriter = new StringWriter();
        e.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
  }

  record StartRequest(int version) {}

  record StartResponse(int version, boolean ready,
                       Implementation implementation) {}

  record DialectRequest(String dialect) {}

  record DialectResponse(boolean ok) {}

  record RunRequest(JsonNode seq, @JsonProperty("case") TestCase testCase) {}

  record RunResponse(JsonNode seq, List<TestResult> results) {}

  record RunErroredResponse(JsonNode seq, boolean errored,
                            ErrorContext context) {}

  record ErrorContext(String message, String traceback) {}

  record TestCase(String description, String comment, JsonNode schema,
                  JsonNode registry, List<Test> tests) {}

  record Test(String description, String comment, JsonNode instance,
              boolean valid) {}

  record TestResult(boolean valid) {}

  record Implementation(String language, String name, String version,
                        List<String> dialects, String homepage, String issues,
                        String os, String os_version, String language_version) {
  }
