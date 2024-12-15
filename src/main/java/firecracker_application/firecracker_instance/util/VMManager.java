package firecracker_application.firecracker_instance.util;

import firecracker_application.firecracker_instance.controller.dto.ResourceRequest;
import firecracker_application.firecracker_instance.controller.dto.ResourceResponse;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

@Component
public class VMManager {

    private static final String BASH_SCRIPT_PATH = "/home/rkdlem48/lambda-implement-firecracker-instance/start_instance.sh";

    public ResourceResponse instanceStart(final ResourceRequest resourceRequest) throws IOException, InterruptedException {
        List<String> outputLines = new ArrayList<>();
        Map<String, String> env = getEnv(resourceRequest);
        ProcessBuilder processBuilder = new ProcessBuilder("bash", BASH_SCRIPT_PATH);
        processBuilder.environment().putAll(env);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean withinOutput = false;
                while ((line = reader.readLine()) != null) {
//                    if (line.contains("SCRIPT_OUTPUT_START")) {
//                        withinOutput = true;
//                    } else if (line.contains("SCRIPT_OUTPUT_END")) {
//                        withinOutput = false;
//                    } else if (withinOutput) {
//                        outputLines.add(line);
//                    }
                    outputLines.add(line);
                }
            }

            for(String str: outputLines){
                System.out.println(str);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Process exited with non-zero status: " + exitCode);
            }

            if (process.exitValue() != 0) {
                throw new RuntimeException("Process exited with non-zero status: " + process.exitValue());
            }

        } catch (Exception e) {
            return new ResourceResponse(Collections.singletonList("Error: " + e.getMessage()));
        }

        return new ResourceResponse(outputLines);
    }

    public Map<String, String> getEnv(final ResourceRequest resourceRequest) {
        Map<String, String> env = new HashMap<>(System.getenv());
        int sbId = generateRandomSbId();

        env.put("VCPU", String.valueOf(Math.max(1, Math.ceil((double) resourceRequest.getRequestMemory() / 2048 * 2) / 2)));
        env.put("MEMORY", String.valueOf(resourceRequest.getRequestMemory()));
        env.put("CODE_LOCATION", resourceRequest.getCodeLocation());
        env.put("SB_ID", String.valueOf(sbId));
        env.put("TAP_DEV", "tap" + sbId);
        env.put("BUCKET_NAME", "your-bucket-name");
        env.put("SCRIPT_PATH", "./test.sh");
        env.put("MASK_LONG", "255.255.255.252");
        env.put("MASK_SHORT", "/30");
        env.put("FC_IP", String.format("176.12.0.%d", ((4 * sbId + 1) % 256)));
        env.put("TAP_IP", String.format("176.12.0.%d", ((4 * sbId + 2) % 256)));
        env.put("FC_MAC", String.format("02:FC:00:00:%02X:%02X", sbId / 256, sbId % 256));
        env.put("ROOTFS", "/home/rkdlem48/implements/ubuntu-24.04.ext4");
        env.put("LOGFILE", "/home/rkdlem48/implements/output/fc-sb" + sbId + "-log");
        env.put("API_SOCKET", "/tmp/firecracker-sb" + sbId + ".socket");
        env.put("KERNEL_BOOT_ARGS", String.format("console=ttyS0 reboot=k panic=1 pci=off ip=%s::%s:%s::eth0:off",
                env.get("FC_IP"), env.get("TAP_IP"), env.get("MASK_LONG")));
        env.put("KERNEL", "/home/rkdlem48/implements/vmlinux-5.10.225");

        return env;
    }

    private static int generateRandomSbId() {
        Random random = new Random();
        int id;
        do {
            id = random.nextInt(255);
        } while (isIpAddressInUse(id));
        return id;
    }

    private static boolean isIpAddressInUse(int id) {
        String[] command = {"/bin/sh", "-c", "ip addr | grep " + "tap" + id};

        try {
            Process process = Runtime.getRuntime().exec(command);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("tap" + id)) {
                        return true;  // IP 주소가 사용 중
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.out.println("Error checking IP address: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Exception while checking IP address" + e);
        }

        return false;  // IP 주소가 사용 중이지 않음
    }
}
