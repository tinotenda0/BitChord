# Self-hosting image: one static binary, no shell, no state.
FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /server .
# Somewhere the account device list can be saved, owned by the user the
# server runs as, so a volume mounted over it starts out writable.
RUN mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /server /server
COPY --from=build --chown=65532:65532 /out/data /data
USER nonroot
ENTRYPOINT ["/server"]
