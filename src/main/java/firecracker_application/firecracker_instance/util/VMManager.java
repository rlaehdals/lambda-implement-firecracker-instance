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

    public ResourceResponse instanceStart(final ResourceRequest resourceRequest) throws IOException, InterruptedException {
        List<String> outputLines = new ArrayList<>();
        Map<String, String> env = getEnv(resourceRequest);
        ProcessBuilder processBuilder = new ProcessBuilder("bash", START_SCRIPT_PATH);
        processBuilder.environment().putAll(env);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            final boolean[] withinOutput = {false}; // 배열 형태로 무상태 유지
        
            // 출력 스트림을 비동기적으로 읽어들이기 위한 스레드 실행
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // SCRIPT_OUTPUT_START와 SCRIPT_OUTPUT_END 기준에 따라 처리합니다.
                        if (line.contains("SCRIPT_OUTPUT_START")) {
                            withinOutput[0] = true; // 시작 지점 발견
                            continue; // 이 줄은 출력하지 않음
                        } else if (line.contains("SCRIPT_OUTPUT_END")) {
                            withinOutput[0] = false; // 종료 지점 발견
                            continue; // 이 줄은 출력하지 않음
                        }
                        System.out.println(line);
                        // 현재 섹션 내에 있을 때만 출력 및 추가
                        if (withinOutput[0]) {
                            if (line.contains("[INFO]")) {
                                outputLines.add(line);
                                System.out.println(line); // 원하는 경우 출력
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Error reading output: " + e.getMessage());
                }
            });
        
            outputThread.start(); // 출력 읽기 스레드 시작
        
            int exitCode = process.waitFor(); // 프로세스 종료 대기
            outputThread.join(); // 출력 스레드가 종료될 때까지 대기합니다.
        
            if (exitCode != 0) {
                throw new RuntimeException("Process exited with non-zero status: " + exitCode);
            }
        
        } catch (Exception e) {
            return new ResourceResponse(Collections.singletonList("Error: " + e.getMessage()));
        }
        

        return new ResourceResponse(outputLines);
    }

    public Map<String, String> getEnv(final ResourceRequest resourceRequest) {
        System.out.println(KERNEL_PATH.replace("vmlinux", resourceRequest.getArchitect() + "_vmlinux"));
        System.out.println(ROOTFS_PATH.replace("ubuntu", resourceRequest.getLanguage() + "_ubuntu"));
        Map<String, String> env = new HashMap<>(System.getenv());
        int sbId = generateRandomSbId();

        env.put("VCPU", String.valueOf(Math.max(1, Math.round(resourceRequest.getRequestMemory() / 2048 * 2) / 2)));
        System.out.println(env.get("VCPU"));
        env.put("MEMORY", String.valueOf(resourceRequest.getRequestMemory()));
        env.put("SB_ID", String.valueOf(sbId));
        env.put("TAP_DEV", "tap" + sbId);
        env.put("BUCKET_NAME", resourceRequest.getBucketName());
        env.put("FILE_PATH", resourceRequest.getFilePath());
        env.put("MASK_LONG", "255.255.255.252");
        env.put("MASK_SHORT", "/30");
        env.put("FC_IP", String.format("176.12.0.%d", ((4 * sbId + 1) % 256)));
        env.put("TAP_IP", String.format("176.12.0.%d", ((4 * sbId + 2) % 256)));
        env.put("FC_MAC", String.format("02:FC:00:00:%02X:%02X", sbId / 256, sbId % 256));
        env.put("ROOTFS", ROOTFS_PATH.replace("ubuntu", resourceRequest.getLanguage() + "_ubuntu"));
        env.put("LOGFILE", LOGFILE_PATH + sbId + "-log");
        env.put("API_SOCKET", "/tmp/firecracker-sb" + sbId + ".socket");
        env.put("KERNEL_BOOT_ARGS", String.format("console=ttyS0 reboot=k panic=1 pci=off ip=%s::%s:%s::eth0:off",
                env.get("FC_IP"), env.get("TAP_IP"), env.get("MASK_LONG")));
        env.put("KERNEL", KERNEL_PATH.replace("vmlinux", resourceRequest.getArchitect() + "_vmlinux"));
        env.put("SSH_KEY_PATH",SSH_KEY_PATH);
        env.put("ENV", resourceRequest.getEnv());
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
