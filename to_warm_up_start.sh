#!/bin/bash

set -euo pipefail

execute_script_in_microvm() {
    echo "SCRIPT_OUTPUT_START"
    # SSH를 통해 S3에서 스크립트 다운로드 및 실행
    ssh -o StrictHostKeyChecking=no -i $SSH_KEY_PATH root@${FC_IP} << EOF
        java -Denv="$ENV" $FILE_PATH | while IFS= read -r line; do
            echo "[INFO] \$line"
        done

        echo "SCRIPT_OUTPUT_END"

        exit
EOF
}

execute_script_in_microvm