FROM ubuntu:16.04

RUN apt-get update && \
    apt-get install -y default-jdk maven && \
    rm -rf /var/lib/apt/lists/*
