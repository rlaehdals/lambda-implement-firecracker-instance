package firecracker_application.firecracker_instance.controller;

import firecracker_application.firecracker_instance.controller.dto.StartVMRequest;
import firecracker_application.firecracker_instance.controller.dto.ToWarmUpRequest;
import firecracker_application.firecracker_instance.util.VMManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RequiredArgsConstructor
@RestController
public class FirecrackerController {

    private final VMManager vmManager;

    @PostMapping("/instance-start")
    public ResponseEntity<Object> request(@RequestBody StartVMRequest request) throws IOException, InterruptedException {
        return ResponseEntity.ok(vmManager.instanceStart(request));
    }

    @PostMapping("/to-warm-up")
    public ResponseEntity<Object> toWarmUp(@RequestBody ToWarmUpRequest request) throws IOException, InterruptedException {
        return ResponseEntity.ok(vmManager.toWarmUpRequest(request));
    }
}
