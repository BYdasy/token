#!/bin/bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
LOG_FILE=${SCRIPT_DIR}/build-local-docker-image.log
ERROR=""
IMAGE_NAME="vben-admin-local"

function stop_and_remove_container() {
    # 停止并移除现有容器
    docker stop ${IMAGE_NAME} >/dev/null 2>&1
    docker rm ${IMAGE_NAME} >/dev/null 2>&1
}

function remove_image() {
    # 移除现有镜像
    docker rmi ${IMAGE_NAME} >/dev/null 2>&1
}

function install_dependencies() {
    # 安装全部依赖
    cd ${SCRIPT_DIR}
    pnpm install || ERROR="install_dependencies failed"
}

function build_image() {
    # 构建 Docker 镜像
    docker build ../../ -f Dockerfile -t ${IMAGE_NAME} || ERROR="build_image failed"
}

function log_message() {
    if [[ ${ERROR} != "" ]];
    then
        >&2 echo "构建失败，请查看 build-local-docker-image.log 获取详细信息"
        >&2 echo "错误：${ERROR}"
        exit 1
    else
        echo "标签为 '${IMAGE_NAME}' 的 Docker 镜像已构建成功，可使用下方示例命令运行容器"
        echo ""
        echo "docker run -d -p 8010:8080 --name ${IMAGE_NAME} ${IMAGE_NAME}"
    fi
}

echo "信息：正在停止并移除现有容器和镜像" | tee ${LOG_FILE}
stop_and_remove_container
remove_image

echo "信息：正在安装依赖" | tee -a ${LOG_FILE}
install_dependencies 1>> ${LOG_FILE} 2>> ${LOG_FILE}

if [[ ${ERROR} == "" ]]; then
    echo "信息：正在构建 Docker 镜像" | tee -a ${LOG_FILE}
    build_image 1>> ${LOG_FILE} 2>> ${LOG_FILE}
fi

log_message | tee -a ${LOG_FILE}
