package firecracker_application.firecracker_instance.util;

import firecracker_application.firecracker_instance.controller.dto.StartVMRequest;
import firecracker_application.firecracker_instance.controller.dto.CommonResponse;
import firecracker_application.firecracker_instance.controller.dto.StartVMResponse;
import firecracker_application.firecracker_instance.controller.dto.ToWarmUpRequest;
import firecracker_application.firecracker_instance.controller.dto.ToWarmUpResponse;

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

    @Value("${firecracker.file.to_warm_up_script}")
    private String TO_WARM_UP_SCRIPT;

    @Value("${firecracker.file.firecracker_finish_script}")
    private String FIRECARCKER_FINISH_SCRIPT_PATH;

    @Value("${firecracker.path}")
    private String FIRECRACKER_PATH;


    public CommonResponse instanceStart(final StartVMRequest startVMRequest) {
        List<String> outputLines = new ArrayList<>();
        Map<String, String> env = getMicroVmStartEnv(startVMRequest);

        try {
            firecrackerStart(env);
            outputLines = executeScript(env, START_SCRIPT_PATH);
        } catch (Exception e) {
            return new CommonResponse(Collections.singletonList("Error: " + e.getMessage()));
        } finally {
            try {
                firecrackerFinish(env);
            } catch (IOException e) {
                // Handle cleanup failure
                System.err.println("Error during firecracker finish: " + e.getMessage());
            }
        }

        return new CommonResponse(new StartVMResponse(outputLines, env.get("FC_IP")));
    }

    public CommonResponse toWarmUpRequest(final ToWarmUpRequest toWarmUpRequest){
        List<String> outputLines = new ArrayList<>();
        Map<String, String> env = getToWarmUpRequestEnv(toWarmUpRequest);

        try {
            outputLines = executeScript(env,TO_WARM_UP_SCRIPT);
        } catch (Exception e) {
            return new CommonResponse(Collections.singletonList("Error: " + e.getMessage()));
        }

        return new CommonResponse(new ToWarmUpResponse(outputLines));
    }

    private List<String> executeScript(final Map<String, String> env, final String scriptPath) throws IOException, InterruptedException {
        List<String> outputLines = new ArrayList<>();
        ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptPath);
        processBuilder.environment().putAll(env);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

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

    private Map<String, String> getMicroVmStartEnv(final StartVMRequest startVMRequest) {
        Map<String, String> env = new HashMap<>(System.getenv());
        int sbId = generateRandomSbId();

        // Set environment variables
        env.put("VCPU", String.valueOf(Math.max(1, Math.round(startVMRequest.getRequestMemory() / 2048 * 2) / 2)));
        env.put("ARN", startVMRequest.getArn());
        env.put("MEMORY", String.valueOf(startVMRequest.getRequestMemory()));
        env.put("SB_ID", String.valueOf(sbId));
        env.put("TAP_DEV", "tap" + sbId);
        env.put("BUCKET_NAME", startVMRequest.getBucketName());
        env.put("FILE_PATH", startVMRequest.getFilePath());
        env.put("MASK_LONG", "255.255.255.252");
        env.put("MASK_SHORT", "/30");
        setIPAddresses(env, sbId);
        setKernelAndRootFSPaths(env, startVMRequest);
        env.put("LOGFILE", LOGFILE_PATH + sbId + "-log");
        env.put("API_SOCKET", "/tmp/firecracker-sb" + sbId + ".socket");
        env.put("SSH_KEY_PATH", SSH_KEY_PATH);
        env.put("ENV", startVMRequest.getEnv());
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

    private void setKernelAndRootFSPaths(Map<String, String> env, StartVMRequest startVMRequest) {
        env.put("BASE_ROOTFS", ROOTFS_PATH.replace("ubuntu", startVMRequest.getLanguage() + "_ubuntu"));
        env.put("COPY_ROOTFS", ROOTFS_PATH.replace("ubuntu", env.get("SB_ID") + "_" + startVMRequest.getLanguage() + "_ubuntu"));
        env.put("KERNEL", KERNEL_PATH.replace("vmlinux", startVMRequest.getArchitect() + "_vmlinux"));
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

    private Map<String, String> getToWarmUpRequestEnv(ToWarmUpRequest toWarmUpRequest){
        Map<String, String> env = new HashMap<>(System.getenv());

        // Set environment variables
        env.put("FC_IP", toWarmUpRequest.getFirecrackerInternalIP());
        env.put("FILE_PATH", toWarmUpRequest.getFilePath());
        env.put("SSH_KEY_PATH", SSH_KEY_PATH);
        env.put("ENV", toWarmUpRequest.getEnv());

        return env;
    }
    
}
