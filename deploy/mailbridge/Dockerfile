# Test-only notification-service stand-in (see mailbridge.py). Standard library only.
FROM python:3.12-slim
RUN useradd --uid 10001 --no-create-home bridge
COPY mailbridge.py /app/mailbridge.py
USER 10001
EXPOSE 8090
CMD ["python", "/app/mailbridge.py"]
