// Command webpush-encrypt encrypts a payload exactly as the CRM does (webpush-go, RecordSize 2700 —
// docs/ANDROID_CONTRACT.md §5) and prints the body base64url: what FCM carries as "p". Nothing is sent anywhere.
//
//	webpush-encrypt <p256dh> <auth> '<json payload>'
package main

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"os"

	webpush "github.com/SherClockHolmes/webpush-go"
)

// capture keeps the body of the request webpush-go builds instead of sending it.
type capture struct{ body []byte }

func (c *capture) RoundTrip(r *http.Request) (*http.Response, error) {
	c.body, _ = io.ReadAll(r.Body)
	return &http.Response{StatusCode: 201, Body: io.NopCloser(bytes.NewReader(nil)), Header: http.Header{}}, nil
}

func main() {
	if len(os.Args) != 4 {
		fmt.Fprintln(os.Stderr, "usage: webpush-encrypt <p256dh> <auth> '<json payload>'")
		os.Exit(2)
	}
	priv, pub, err := webpush.GenerateVAPIDKeys()
	if err != nil {
		panic(err)
	}
	c := &capture{}
	_, err = webpush.SendNotification([]byte(os.Args[3]), &webpush.Subscription{Endpoint: "https://example.invalid/x",
		Keys: webpush.Keys{P256dh: os.Args[1], Auth: os.Args[2]}}, &webpush.Options{HTTPClient: &http.Client{Transport: c},
		Subscriber: "test@example.invalid", VAPIDPublicKey: pub, VAPIDPrivateKey: priv, TTL: 60, RecordSize: 2700})
	if err != nil {
		panic(err)
	}
	fmt.Print(base64.RawURLEncoding.EncodeToString(c.body))
}
