FROM criyle/go-judge:v1.13.3@sha256:97bec66d054e4d844e0ad29dfdd5c79ef3d09efba25b06dd58643a6e99918dcd AS go-judge

FROM eclipse-temurin:17-jdk-jammy@sha256:73ed442231ec66cfe48cae0b263ce619a75ab96d162ab325f38fc0a269ee3aab

RUN apt-get update -o Acquire::Retries=3 \
    && apt-get install -y --no-install-recommends -o Acquire::Retries=3 \
      g++ \
      python3 \
      wget \
    && mkdir -p /usr/lib/jvm \
    && cp -a /opt/java/openjdk /usr/lib/jvm/temurin-17 \
    && ln -sf /usr/lib/jvm/temurin-17/bin/java /usr/bin/java \
    && ln -sf /usr/lib/jvm/temurin-17/bin/javac /usr/bin/javac \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt
COPY --from=go-judge /opt/go-judge /opt/mount.yaml /opt/

EXPOSE 8090/tcp 5051/tcp 5052/tcp

ENTRYPOINT ["./go-judge"]
