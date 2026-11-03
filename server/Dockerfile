# Built from the repo root: docker build -f server/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server .
EXPOSE 8080
CMD ["bin/server"]
