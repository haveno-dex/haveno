#!/bin/bash

APPVM_NAME=haveno

while getopts 'n:h' flag; do
	case "${flag}" in
		n) APPVM_NAME=${OPTARG} ;;
		h) printf "Usage: refresh-haveno-address.sh [options
				-n APPVM_NAME : (optional) name of haveno appvm
				-h : print this message
			"
	esac
done

## This script creates a new hiddenservice address on sys-whonix
## Also updates haveno-service-address file
qvm-run -u root --pass-io -- sys-whonix "rm -rf /var/lib/tor/haveno_service/ && service tor@default restart"

SERVICE="$(qvm-run -u root --pass-io -- sys-whonix 'cat /var/lib/tor/haveno_service/hostname')"

qvm-run -u root --pass-io -- $APPVM_NAME "echo $SERVICE > /root/haveno-service-address"

