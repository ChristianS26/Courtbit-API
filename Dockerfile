# Etapa 1: Build con Gradle
FROM gradle:8.10.2-jdk17-jammy AS builder
# puedes usar otra versión 8.x reciente si prefieres

WORKDIR /app
COPY . /app

# Descargamos dependencias y construimos el proyecto
RUN gradle clean installDist --info --no-daemon

# Etapa 2: Imagen final ligera solo con JRE y el binario generado
FROM eclipse-temurin:17-jre-jammy
# Si necesitas JDK completo, puedes usar: eclipse-temurin:17-jdk-jammy

WORKDIR /app

# Copiamos el artefacto ya compilado
COPY --from=builder /app/build/install/ktor-lagartosapp /app

# Railway provee esta variable automáticamente, pero no estorba
ENV PORT=8080

# (Opcional, pero recomendado)
EXPOSE 8080

# Comando para iniciar tu aplicación
CMD ["./bin/ktor-lagartosapp"]
