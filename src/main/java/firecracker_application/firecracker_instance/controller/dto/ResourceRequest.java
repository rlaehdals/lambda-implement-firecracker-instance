package firecracker_application.firecracker_instance.controller.dto;

import lombok.Getter;
import lombok.ToString;

@Getter
public class ResourceRequest {
    private Integer requestMemory;
    private String architect;
    private String language;
    private String codeLocation;
}
