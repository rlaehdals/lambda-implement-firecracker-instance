#!/bin/bash

ssh -o StrictHostKeyChecking=no -i $SSH_KEY_PATH root@${FC_IP} << EOF

    sleep 60

    if [ $? -eq 0 ]; then
        echo "Java 프로세스가 실행 중입니다. 기다리는 중..."
        wait $(pgrep -x java)
        echo "Java 프로세스가 종료되었습니다."
    else
        echo "Java 프로세스가 실행되고 있지 않습니다."
    fi

    aws dynamodb delete-item \
    --table-name warmUpInfo \
    --key '{"arn": {"S": "'"${FC_IP}"'"}}'

    reboot
EOF

rm -rf $COPY_ROOTFS