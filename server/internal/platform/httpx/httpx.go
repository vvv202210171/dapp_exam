// 访问节点的 HTTP 客户端。超时 12 秒，调用方只传白名单里的地址，避免变成开放代理。
package httpx

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	HTTP *http.Client
}

// 创建一个 12 秒超时的 HTTP 客户端。
func New() *Client {
	return &Client{HTTP: &http.Client{Timeout: 12 * time.Second}}
}

// POST JSON，并把响应解到 out。
func (c *Client) PostJSON(ctx context.Context, endpoint string, in any, out any) error {
	body, err := json.Marshal(in)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "AegisLearningWallet/1.0")
	return c.do(req, out)
}

// GET JSON，并把响应解到 out。
func (c *Client) GetJSON(ctx context.Context, endpoint string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "AegisLearningWallet/1.0")
	return c.do(req, out)
}

// POST 一段纯文本，返回响应文本。
func (c *Client) PostText(ctx context.Context, endpoint, body string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "text/plain")
	req.Header.Set("User-Agent", "AegisLearningWallet/1.0")
	return c.text(req)
}

// GET 并返回响应文本。
func (c *Client) GetText(ctx context.Context, endpoint string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "AegisLearningWallet/1.0")
	return c.text(req)
}

// 执行请求，非 2xx 时把响应体夹进错误。
func (c *Client) do(req *http.Request, out any) error {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode == http.StatusTooManyRequests {
		return fmt.Errorf("429 rate limit")
	}
	if resp.StatusCode >= 400 {
		return fmt.Errorf("http %d: %s", resp.StatusCode, clip(string(body)))
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("解析节点响应失败: %w", err)
	}
	return nil
}

// 执行请求并读取文本响应。
func (c *Client) text(req *http.Request) (string, error) {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	text := strings.TrimSpace(string(b))
	if resp.StatusCode == http.StatusNotFound {
		return "", fmt.Errorf("404 not found")
	}
	if resp.StatusCode == http.StatusTooManyRequests {
		return "", fmt.Errorf("429 rate limit")
	}
	if resp.StatusCode >= 400 {
		return "", fmt.Errorf("http %d: %s", resp.StatusCode, clip(text))
	}
	return text, nil
}

// 从 URL 里取出主机名，日志里不打印完整地址。
func Host(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "configured-endpoint"
	}
	return u.Scheme + "://" + u.Host
}

// 截断过长的响应，避免日志或错误被撑爆。
func clip(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > 240 {
		return s[:240] + "…"
	}
	return s
}
