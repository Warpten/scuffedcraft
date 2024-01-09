package wrptn.scuffedcraft.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpscArrayQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import wrptn.scuffedcraft.models.SimulationInput;
import wrptn.scuffedcraft.simulation.Ticket;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static wrptn.scuffedcraft.json.Builders.object;

@Controller
@Slf4j
@RequestMapping(path = "/v2")
public class WebFluxSimulationController {
    @Value("${simulationcraft.executable.location}")
    private String executablePath;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Ticket> simulationTickets = new HashMap<>();
    private final MpscArrayQueue<Ticket> jobQueue = new MpscArrayQueue<>(64);
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(  1);

    public WebFluxSimulationController() {
        this.executorService.scheduleAtFixedRate(() -> {
            Ticket ticket = this.jobQueue.poll();
            if (ticket == null)
                return;

            log.info("Executing {}.", ticket.getInput().getRequestUUID());
            ticket.submit(this.executablePath);
        }, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void preDestroy() {
        this.executorService.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!this.executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                this.executorService.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!this.executorService.awaitTermination(60, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ex) {
            // (Re-)Cancel if current thread also interrupted
            this.executorService.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping(path = "/")
    public String index(Model model) {
        model.addAttribute("simulationInput", new SimulationInput());

        return "simulation";
    }

    @GetMapping(path = "/simulation-progress/{requestUUID}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<String> getSimulationProgress(@PathVariable String requestUUID) throws Exception {
        var simulationTicket = this.simulationTickets.get(requestUUID);
        if (simulationTicket == null) {
            var objectNode = object("type", "error")
                .with("message", "Simulation job not found. This is a backend problem; please check back later.")
                .end();
            return Flux.just(objectMapper.writeValueAsString(objectNode));
        }

        return simulationTicket.getResultsFlux().delaySequence(Duration.ofSeconds(5));
    }

    @PostMapping(path = "/")
    public String executeSimulation(@ModelAttribute SimulationInput input) {
        input.setFormSubmit(true);

        var queuePosition = this.simulationTickets.size() + 1;

        var simulationTicket = new Ticket(input, queuePosition);
        simulationTicket.registerListener(new Ticket.Listener() {
            @Override
            public void onBegin() {
                for (Ticket itr : WebFluxSimulationController.this.simulationTickets.values())
                    if (simulationTicket != itr)
                        itr.advance();
            }

            @Override
            public void onCompleted() {
                WebFluxSimulationController.this.simulationTickets.remove(simulationTicket.getInput().getRequestUUID());
            }
        });

        boolean offerSuccesfull = this.jobQueue.offer(simulationTicket);
        if (!offerSuccesfull) {
            simulationTicket.emitThrowable(new Exception("The job queue is full. Please check back later."));
        } else {
            this.simulationTickets.put(input.getRequestUUID(), simulationTicket);
        }


        return "simulation";
    }
}
