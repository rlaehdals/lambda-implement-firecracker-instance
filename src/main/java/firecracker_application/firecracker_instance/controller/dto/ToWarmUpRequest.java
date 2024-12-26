package firecracker_application.firecracker_instance.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ToWarmUpRequest {
    private String firecrackerInternalIP;
    private String filePath;
    private String env;
}
