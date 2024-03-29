package wrptn.scuffedcraft.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import wrptn.scuffedcraft.models.SimulationInput;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.lang.System.lineSeparator;
import static java.util.stream.Collectors.joining;
import static org.springframework.util.StringUtils.hasText;
import static wrptn.scuffedcraft.json.Builders.object;

public class Ticket {
    private final Sinks.Many<String> resultsSink = Sinks.many().unicast().onBackpressureBuffer();

    @Getter
    private final SimulationInput input;

    private final Logger log;

    @Getter
    private int queuePosition;

    private Process executionTask = null;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public interface Listener {
        default void onCompleted() { }
        default void onBegin() { }
    }
    private Listener listener = new Listener() { };

    public Ticket(@NonNull SimulationInput input, int queuePosition) {
        this.input = input;
        this.queuePosition = queuePosition;

        this.log = LoggerFactory.getLogger(input.getRequestUUID());
    }

    public void registerListener(Listener listener) {
        this.listener = listener;
    }

    public Flux<String> getResultsFlux() { return this.resultsSink.asFlux(); }

    @SneakyThrows
    public void advance() {
        this.queuePosition = this.queuePosition - 1;

        var objectNode = object("type", "queue").with("position", this.queuePosition).end();
        var serializedValue = objectMapper.writeValueAsString(objectNode);

        this.resultsSink.tryEmitNext(serializedValue);
    }

    public boolean trySubmit(final MessagePassingQueue<Ticket> jobQueue) {
        boolean offerSuccesfull = jobQueue.offer(this);
        if (!offerSuccesfull) {
            this.emitThrowable(new Exception("The job queue is full. Please check back later."));
        }

        return offerSuccesfull;
    }

    public void submit(String executablePath) {
        this.emitValue(object("type", "status").with("status", "IN_PROGRESS").end());
        this.listener.onBegin();

        try {
            this.invokeSimulationCraft(executablePath);
        } catch (Exception ex) {
            emitThrowable(ex);
        } finally {
            this.listener.onCompleted();
        }
    }

    @SneakyThrows
    private void emitValue(ObjectNode node) {
        var serializedValue = objectMapper.writeValueAsString(node);

        this.resultsSink.tryEmitNext(serializedValue);
    }

    /**
     * Sends a failure reason to the client.
     *
     * @param ex The exception that has been thrown.
     */
    @SneakyThrows
    public void emitThrowable(Throwable ex) {
        var errorMessage = "An unknown error occured";
        if (ex != null && hasText(ex.getMessage()))
            errorMessage = ex.getMessage();

        var objectNode = object("type", "error").with("message", errorMessage).end();
        this.emitValue(objectNode);

        this.closeSink();;
    }

    private void closeSink() {
        this.emitValue(object("type", "end").end());

        this.resultsSink.tryEmitComplete();
    }

    @SneakyThrows
    private void invokeSimulationCraft(String executablePath) {
        Path inputPath = null;
        Path reportPath = null;
        Path logsPath = null;
        try {
            inputPath = Files.createTempFile("simc_input_", ".txt");
            reportPath = Paths.get(inputPath.getParent().toString(), "simc_output_" + UUID.randomUUID() + ".txt");
            logsPath = Files.createTempFile( "simc_logs_", ".txt");

            log.info("Beginning simulation with input from '{}' and output to '{}'. Logs are written to '{}'.",
                inputPath.getFileName(), reportPath.getFileName(), logsPath.getFileName());

            var inputWriter = new BufferedWriter(new FileWriter(inputPath.toFile()));

            inputWriter.write("item_db_source=local" + System.lineSeparator());
            inputWriter.write("target_error=0" + System.lineSeparator());
            inputWriter.write("iterations=0" + System.lineSeparator());
            inputWriter.write("default_world_lag=0.1" + System.lineSeparator());
            inputWriter.write("max_time=300" + System.lineSeparator());
            inputWriter.write("vary_combat_length=0.2" + System.lineSeparator());
            inputWriter.write("fight_style=" + this.input.getFightType().getDisplayName() + System.lineSeparator());
            inputWriter.write("tmi_window_global=6" + System.lineSeparator());
            inputWriter.write("target_level+=3" + System.lineSeparator());
            inputWriter.write("target_race=Humanoid" + System.lineSeparator());
            inputWriter.write("optimal_raid=0" + System.lineSeparator());
            inputWriter.write("override.bloodlust=" + (this.input.isEnableBloodlust() ? 1 : 0) + System.lineSeparator());
            inputWriter.write("override.bleeding=1" + System.lineSeparator());
            inputWriter.write("override.mortal_wounds=1" + System.lineSeparator());
            inputWriter.write("threads=4" + System.lineSeparator());
            inputWriter.write("process_priority=Low" + System.lineSeparator());
            if (input.isEnableScaling()) {
                inputWriter.write("calculate_scale_factors=1" + System.lineSeparator());
                inputWriter.write("scale_only=str,agi,int,crit,haste,mastery,vers" + System.lineSeparator());
            }
            inputWriter.write("statistics_level=1" + System.lineSeparator());
            inputWriter.write(lineSeparator());

            // Sanitize the user's submitted profile so that they don't cause excessive load on the server.
            input.getProfile().forEach(line -> {
                try {
                    inputWriter.write(line);
                    inputWriter.write(lineSeparator());
                } catch (IOException ignored) { }
            });

            inputWriter.write(lineSeparator());
            inputWriter.write("desired_targets=" + input.getNumberOfEnemies() + System.lineSeparator());
            inputWriter.write("dps_plot_stat=none" + System.lineSeparator());

            inputWriter.close();

            var simcExecutable = Paths.get(executablePath);

            var processBuilder = new ProcessBuilder()
                .directory(reportPath.getParent().toFile())
                .command(simcExecutable.toString(), inputPath.toString(), "html=" + reportPath.getFileName())
                .redirectOutput(ProcessBuilder.Redirect.to(logsPath.toFile()))
                ;

            this.executionTask = processBuilder.start();

            boolean exitSuccessfully = this.executionTask.waitFor(input.isEnableScaling() ? 10 : 1, TimeUnit.MINUTES);
            if (!exitSuccessfully)
                throw new TimeoutException("Execution timed out");

            // Read report file
            try (var reportReader = new BufferedReader(new FileReader(reportPath.toFile()))) {
                this.resultsSink.tryEmitNext("results;" + reportReader.lines().collect(joining(lineSeparator())));
            }

            try (var reportReader = new BufferedReader(new FileReader(logsPath.toFile()))) {
                this.emitValue(object("type", "log")
                    .with("line", reportReader.lines().collect(joining(lineSeparator()))).end());
            }

            this.closeSink();
        } finally {
            if (inputPath != null)
                inputPath.toFile().delete();

            if (reportPath != null)
                reportPath.toFile().delete();

            if (logsPath != null)
                logsPath.toFile().delete();
        }
    }
}
