package firecracker_application.firecracker_instance.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class ToWarmUpResponse {
    private List<String> outputLines;
}
