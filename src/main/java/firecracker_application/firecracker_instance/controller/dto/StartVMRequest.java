package firecracker_application.firecracker_instance.controller.dto;

import lombok.Getter;

@Getter
public class StartVMRequest {
    private Integer requestMemory;
    private String architect;
    private String language;
    private String arn;
    private String bucketName;
    private String filePath;
    private String env;
}
