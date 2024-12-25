#!/bin/bash

ssh -o StrictHostKeyChecking=no -i $SSH_KEY_PATH root@${FC_IP} << EOF
    sleep 60
    reboot
EOF

rm -rf $COPY_ROOTFS