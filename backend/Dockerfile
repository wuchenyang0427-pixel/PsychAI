# ============================================================
# 后端镜像：多阶段构建
#   阶段 1 用 Maven 编译打包，阶段 2 只保留 JRE + jar
#   这样最终镜像不含 Maven 仓库和源码，体积小很多
# ============================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom 并预下载依赖：pom 没变时这层直接命中缓存，后续改代码不用重新下依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 容器默认 UTC，不设时区日志时间会比北京时间早 8 小时
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage 让 JVM 按容器内存上限自动分配堆，避免被 OOM Kill
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
