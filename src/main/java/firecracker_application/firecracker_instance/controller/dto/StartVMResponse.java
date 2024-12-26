package firecracker_application.firecracker_instance.controller.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StartVMResponse {
    private List<String> output;
    private String firecrackerInternalIP;
}