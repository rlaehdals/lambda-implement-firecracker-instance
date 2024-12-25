#!/bin/bash

set -euo pipefail

# Function to generate a random SB_ID
curl_put() {
    local URL_PATH="$1"
    local OUTPUT RC
    OUTPUT="$("${CURL[@]}" -X PUT --data @- "http://localhost/${URL_PATH#/}" 2>&1)"
    RC="$?"
    if [ "$RC" -ne 0 ]; then
        echo "Error: curl PUT ${URL_PATH} failed with exit code $RC, output:"
        echo "$OUTPUT"
        return 1
    fi
    # Error if output doesn't end with "HTTP 2xx"
    if [[ "$OUTPUT" != *HTTP\ 2[0-9][0-9] ]]; then
        echo "Error: curl PUT ${URL_PATH} failed with non-2xx HTTP status code, output:"
        echo "$OUTPUT"
        return 1
    fi
}

wait_for_cp() {
    while :; do
        # dd 프로세스의 수를 확인
        cp_count=$(pgrep -x -c "cp" || echo 0)

        # 프로세스 수가 2 이상이면 대기
        if [ "$cp_count" -ge 1 ]; then
            echo "dd 프로세스가 $cp_count 개 실행 중입니다. 잠시 기다립니다..."
            sleep 0.5  # 0.5초 대기
        else
            echo "dd 프로세스가 종료되었습니다. 계속 진행합니다."
            break
        fi
    done
}

start_microvm_with_network() {
    while [ ! -e "$API_SOCKET" ]; do
        echo "FC $SB_ID still not ready..."
        sleep 0.01
    done

    ip link del "$TAP_DEV" 2> /dev/null || true
    ip tuntap add dev "$TAP_DEV" mode tap
    ip link set dev "$TAP_DEV" up
    ip addr add "${TAP_IP}${MASK_SHORT}" dev "$TAP_DEV"
    sysctl -w net.ipv4.conf.${TAP_DEV}.proxy_arp=1 > /dev/null
    sysctl -w net.ipv6.conf.${TAP_DEV}.disable_ipv6=1 > /dev/null

    # 로그 설정
    curl_put "/logger" <<EOF
{
    "level": "Info",
    "log_path": "$LOGFILE",
    "show_level": false,
    "show_log_origin": false
}
EOF

    # 머신 설정
    curl_put "/machine-config" <<EOF
{
    "vcpu_count": $VCPU,
    "mem_size_mib": $MEMORY
}
EOF

    # 부팅 소스 설정
    curl_put "/boot-source" <<EOF
{
    "kernel_image_path": "$KERNEL",
    "boot_args": "$KERNEL_BOOT_ARGS"
}
EOF

    wait_for_cp
    cp --reflink=auto $BASE_ROOTFS $COPY_ROOTFS

   # 루트 파일 시스템 설정
    curl_put "/drives/$SB_ID" <<EOF
{
    "drive_id": "$SB_ID",
    "path_on_host": "$COPY_ROOTFS",
    "is_root_device": true,
    "is_read_only": false
}
EOF

    # 네트워크 인터페이스 설정
    curl_put "/network-interfaces/$SB_ID" <<EOF
{
    "iface_id": "$SB_ID",
    "guest_mac": "$FC_MAC",
    "host_dev_name": "$TAP_DEV"
}
EOF

    # microVM 시작
    curl_put '/actions' <<EOF
{
    "action_type": "InstanceStart"
}
EOF

    echo "InstanceStart command executed"

    sleep 3
}

execute_script_in_microvm() {
    echo "SCRIPT_OUTPUT_START"
    # SSH를 통해 S3에서 스크립트 다운로드 및 실행
    ssh -o StrictHostKeyChecking=no -i $SSH_KEY_PATH root@${FC_IP} << EOF
        echo [INFO] "$FC_IP"

        ip route add default via $TAP_IP dev eth0
        echo 'nameserver 8.8.8.8' > /etc/resolv.conf

        aws s3 cp s3://$BUCKET_NAME/$FILE_PATH /root

        java -Denv="$ENV" $FILE_PATH | while IFS= read -r line; do
            echo "[INFO] \$line"
        done

        echo "SCRIPT_OUTPUT_END"
EOF
}

CURL=(curl --silent --show-error --header "Content-Type: application/json" --unix-socket "${API_SOCKET}" --write-out "HTTP %{http_code}")
touch $LOGFILE

# Start the microVM with networking
start_microvm_with_network

execute_script_in_microvm

exit 0