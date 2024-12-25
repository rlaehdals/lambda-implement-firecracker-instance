package firecracker_application.firecracker_instance.util;

import firecracker_application.firecracker_instance.controller.dto.ResourceRequest;
import firecracker_application.firecracker_instance.controller.dto.ResourceResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

@Component
public class VMManager {

    @Value("${firecracker.file.ssh-key}")
    private String SSH_KEY_PATH;

    @Value("${firecracker.file.rootfs}")
    private String ROOTFS_PATH;

    @Value("${firecracker.file.kernel}")
    private String KERNEL_PATH;

    @Value("${firecracker.file.logfile}")
    private String LOGFILE_PATH;

    @Value("${firecracker.file.start_script}")
    private String START_SCRIPT_PATH;

    @Value("${firecracker.file.firecracker_finish_script}")
    private String FIRECARCKER_FINISH_SCRIPT_PATH;

    @Value("${firecracker.path}")
    private String FIRECRACKER_PATH;


    public ResourceResponse instanceStart(final ResourceRequest resourceRequest) {
        List<String> outputLines = new ArrayList<>();
        Map<String, String> env = getEnv(resourceRequest);

        try {
            firecrackerStart(env);
            outputLines = executeStartScript(env);
        } catch (Exception e) {
            return new ResourceResponse(Collections.singletonList("Error: " + e.getMessage()));
        } finally {
            try {
                firecrackerFinish(env);
            } catch (IOException e) {
                // Handle cleanup failure
                System.err.println("Error during firecracker finish: " + e.getMessage());
            }
        }

        return new ResourceResponse(outputLines);
    }

    private List<String> executeStartScript(Map<String, String> env) throws IOException, InterruptedException {
        List<String> outputLines = new ArrayList<>();
        ProcessBuilder instanceStartProcessBuilder = new ProcessBuilder("bash", START_SCRIPT_PATH);
        instanceStartProcessBuilder.environment().putAll(env);
        instanceStartProcessBuilder.redirectErrorStream(true);

        Process process = instanceStartProcessBuilder.start();

        // Asynchronously read the output
        Thread outputThread = new Thread(() -> readOutput(process, outputLines));
        outputThread.start();

        int exitCode = process.waitFor(); // Wait for the process to complete
        outputThread.join(); // Wait for the output thread to finish

        if (exitCode != 0) {
            throw new RuntimeException("Process exited with non-zero status: " + exitCode);
        }

        return outputLines;
    }

    private void readOutput(Process process, List<String> outputLines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean withinOutput = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("SCRIPT_OUTPUT_START")) {
                    withinOutput = true; // Start of relevant output
                    continue;
                } else if (line.contains("SCRIPT_OUTPUT_END")) {
                    withinOutput = false; // End of relevant output
                    continue;
                }
                System.out.println(line); 
                if (withinOutput && line.contains("[INFO]")) {
                    outputLines.add(line);
                    // System.out.println(line); // Optional: Print the line
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading output: " + e.getMessage());
        }
    }

    private Map<String, String> getEnv(final ResourceRequest resourceRequest) {
        Map<String, String> env = new HashMap<>(System.getenv());
        int sbId = generateRandomSbId();

        // Set environment variables
        env.put("VCPU", String.valueOf(Math.max(1, Math.round(resourceRequest.getRequestMemory() / 2048 * 2) / 2)));
        env.put("MEMORY", String.valueOf(resourceRequest.getRequestMemory()));
        env.put("SB_ID", String.valueOf(sbId));
        env.put("TAP_DEV", "tap" + sbId);
        env.put("BUCKET_NAME", resourceRequest.getBucketName());
        env.put("FILE_PATH", resourceRequest.getFilePath());
        env.put("MASK_LONG", "255.255.255.252");
        env.put("MASK_SHORT", "/30");
        setIPAddresses(env, sbId);
        setKernelAndRootFSPaths(env, resourceRequest);
        env.put("LOGFILE", LOGFILE_PATH + sbId + "-log");
        env.put("API_SOCKET", "/tmp/firecracker-sb" + sbId + ".socket");
        env.put("SSH_KEY_PATH", SSH_KEY_PATH);
        env.put("ENV", resourceRequest.getEnv());
        env.put("FIRECRACKER_PATH", FIRECRACKER_PATH);
        
        System.out.println(env.get("BASE_ROOTFS"));
        System.out.println(env.get("COPY_ROOTFS"));
        return env;
    }

    private void setIPAddresses(Map<String, String> env, int sbId) {
        env.put("FC_IP", String.format("176.12.0.%d", ((4 * sbId + 1) % 256)));
        env.put("TAP_IP", String.format("176.12.0.%d", ((4 * sbId + 2) % 256)));
        env.put("FC_MAC", String.format("02:FC:00:00:%02X:%02X", sbId / 256, sbId % 256));
    }

    private void setKernelAndRootFSPaths(Map<String, String> env, ResourceRequest resourceRequest) {
        env.put("BASE_ROOTFS", ROOTFS_PATH.replace("ubuntu", resourceRequest.getLanguage() + "_ubuntu"));
        env.put("COPY_ROOTFS", ROOTFS_PATH.replace("ubuntu", env.get("SB_ID") + "_" + resourceRequest.getLanguage() + "_ubuntu"));
        env.put("KERNEL", KERNEL_PATH.replace("vmlinux", resourceRequest.getArchitect() + "_vmlinux"));
        env.put("KERNEL_BOOT_ARGS", String.format("console=ttyS0 reboot=k panic=1 pci=off ip=%s::%s:%s::eth0:off",
                env.get("FC_IP"), env.get("TAP_IP"), env.get("MASK_LONG")));
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
                        return true;  // IP address in use
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.out.println("Error checking IP address: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("Exception while checking IP address: " + e);
        }

        return false;  // IP address not in use
    }

    private void firecrackerStart(Map<String, String> env) throws IOException {
        ProcessBuilder firecrackerStartBuilder = new ProcessBuilder(FIRECRACKER_PATH + "/firecracker",
                "--api-sock",
                env.get("API_SOCKET"));

        firecrackerStartBuilder.environment().putAll(env);
        firecrackerStartBuilder.start();
    }

    private void firecrackerFinish(Map<String, String> env) throws IOException {
        ProcessBuilder firecrackerFinishBuilder = new ProcessBuilder("bash", FIRECARCKER_FINISH_SCRIPT_PATH);
        firecrackerFinishBuilder.environment().putAll(env);
        firecrackerFinishBuilder.start();
    }
    
}
