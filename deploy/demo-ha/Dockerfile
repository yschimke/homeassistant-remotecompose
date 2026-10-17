FROM python:3.13.9-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY gateway.py bootstrap.py entities.json dashboard.json ./
USER 65532:65532
CMD ["python", "gateway.py"]
