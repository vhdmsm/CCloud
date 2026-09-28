FROM python:3.12-slim
WORKDIR /app
COPY relay.py .
# Cache database lives in the /data volume
VOLUME /data
EXPOSE 8080
CMD ["python", "-u", "relay.py"]
