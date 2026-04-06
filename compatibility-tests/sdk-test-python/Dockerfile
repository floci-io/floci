FROM python:3.12-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY conftest.py pytest.ini ./
COPY tests/ tests/

ENV FLOCI_ENDPOINT=http://floci:4566

RUN mkdir -p /results
ENTRYPOINT ["pytest", "tests/", "-v", "--junit-xml=/results/junit.xml"]
