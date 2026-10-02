# Self-hosting image: one static binary, no shell, no state.
FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /server .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /server /server
USER nonroot
ENTRYPOINT ["/server"]
