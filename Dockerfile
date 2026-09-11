FROM maven:3.9.16-amazoncorretto-25 AS build
WORKDIR /app

# cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

#TO compile the service into a jar excecutable file
COPY src ./src
RUN mvn clean package -DskipTests

#Stage 2: Runtime
FROM amazoncorretto:25
WORKDIR /app

#Copy the jar
COPY --from=build /app/target/*.jar app.jar

#Expose the port
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]