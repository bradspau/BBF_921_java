.PHONY: build test run run-access seed clean

build:
	./mvnw package -DskipTests

test:
	./mvnw test

test-cov:
	./mvnw test jacoco:report
	@echo "Coverage report: target/site/jacoco/index.html"

run:
	./mvnw spring-boot:run

run-access:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=access

seed:
	@echo "Seeding default domain (port 8000)..."
	@bash seed_data/seed.sh

clean:
	./mvnw clean
	rm -rf tdb2-data/ tdb2-access/ tdb2-aggregation/
