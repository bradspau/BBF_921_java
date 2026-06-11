# Requires Java 21 + Maven 3.9+. Install via: sudo apt install openjdk-21-jdk maven
# Or build inside Docker: docker compose run --rm app mvn test

.PHONY: build test test-cov run run-access seed clean

build:
	mvn package -DskipTests

test:
	mvn test

test-cov:
	mvn test jacoco:report
	@echo "Coverage report: target/site/jacoco/index.html"

run:
	mvn spring-boot:run

run-access:
	mvn spring-boot:run -Dspring-boot.run.profiles=access

seed:
	@echo "Seeding default domain (port 8000)..."
	@bash seed_data/seed.sh

clean:
	mvn clean
	rm -rf tdb2-data/ tdb2-access/ tdb2-aggregation/
