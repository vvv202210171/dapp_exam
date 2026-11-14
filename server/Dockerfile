FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/aegis ./cmd/api

FROM alpine:3.20
RUN adduser -D app
USER app
COPY --from=build /out/aegis /usr/local/bin/aegis
ENV ADDR=:8080 MODE=sandbox NETWORK=testnet DB_PATH=/data/aegis.db DATA_DIR=/data
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["aegis"]
