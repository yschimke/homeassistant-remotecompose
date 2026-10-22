FROM python:3.15.1-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY gateway.py bootstrap.py verify_public.py native_gateway.py entities.json dashboard.json ./
USER 65532:65532
CMD ["python", "gateway.py"]
