// HTTP 接口。卡和转账接口目前没有登录态，这是学习项目的已知缺口，不要直接暴露到公网。
package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"time"

	"aegis/internal/chain"
	"aegis/internal/domain"
	"aegis/internal/platform/fault"
	"aegis/internal/service"
)

type Server struct {
	Accounts  *service.AccountService
	Assets    *service.AssetService
	Transfers *service.TransferService
	Cards     *service.CardService
	Chains    *chain.Registry
}

// 把账户、资产、转账、卡和链注册到 HTTP 服务。
func New(accounts *service.AccountService, assets *service.AssetService, transfers *service.TransferService, cards *service.CardService, chains *chain.Registry) *Server {
	return &Server{Accounts: accounts, Assets: assets, Transfers: transfers, Cards: cards, Chains: chains}
}

// 注册全部 /api/v1 路由。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.health)
	mux.HandleFunc("GET /api/v1/meta", s.meta)
	mux.HandleFunc("POST /api/v1/accounts/sync", s.syncAccount)
	mux.HandleFunc("GET /api/v1/accounts/{id}", s.getAccount)
	mux.HandleFunc("GET /api/v1/accounts/{id}/assets", s.assets)
	mux.HandleFunc("GET /api/v1/accounts/{id}/transfers", s.listTransfers)
	mux.HandleFunc("POST /api/v1/transfers/prepare", s.prepare)
	mux.HandleFunc("POST /api/v1/transfers", s.submit)
	mux.HandleFunc("GET /api/v1/transfers/{id}", s.getTransfer)
	mux.HandleFunc("POST /api/v1/transfers/{id}/broadcast", s.broadcast)
	mux.HandleFunc("POST /api/v1/transfers/{id}/refresh", s.refresh)
	mux.HandleFunc("GET /api/v1/chains/health", s.chainHealth)
	mux.HandleFunc("GET /api/v1/chains/{chain}/probes", s.probes)
	mux.HandleFunc("POST /api/v1/chains/{chain}/probes/{name}", s.runProbe)
	mux.HandleFunc("POST /api/v1/accounts/{id}/cards", s.createCard)
	mux.HandleFunc("GET /api/v1/accounts/{id}/cards", s.listCards)
	mux.HandleFunc("GET /api/v1/cards/{id}", s.getCard)
	mux.HandleFunc("POST /api/v1/cards/{id}/reveal", s.revealCard)
	mux.HandleFunc("POST /api/v1/cards/{id}/topup", s.topup)
	mux.HandleFunc("POST /api/v1/cards/{id}/spend", s.spend)
	mux.HandleFunc("POST /api/v1/cards/{id}/withdraw", s.withdraw)
	mux.HandleFunc("POST /api/v1/cards/{id}/transition", s.transition)
	mux.HandleFunc("GET /api/v1/cards/{id}/bills", s.bill)
	return logRequests(cors(mux))
}

// 返回进程是否在听请求。
func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// 返回模式、网络、链清单和给前端的警告。
func (s *Server) meta(w http.ResponseWriter, r *http.Request) {
	warnings := []string{}
	if s.Chains.Mode() == "sandbox" {
		warnings = append(warnings, "当前是沙箱模式：余额是按地址稳定生成的模拟值，广播不会发到真实网络。签名用的 EVM chainId 是 84532026，不能在主网或测试网重放。")
	} else if s.Chains.Network() == "mainnet" {
		warnings = append(warnings, "当前指向主网。学习时不要使用存有真实资金的助记词。")
	} else {
		warnings = append(warnings, "当前是 live + testnet。节点是公共 RPC，失败时看错误里的 hint。")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"mode":       s.Chains.Mode(),
		"network":    s.Chains.Network(),
		"chains":     s.Chains.Chains(),
		"warnings":   warnings,
		"settlement": map[string]string{"name": "CardSettlement", "note": "合约在 contracts/。充值、结算、提现的链上版本用 USDC，和沙箱卡账本是两条账。"},
	})
}

// 接收五条链地址并同步账户。
func (s *Server) syncAccount(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Label     string           `json:"label"`
		Addresses []domain.Address `json:"addresses"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	acc, err := s.Accounts.Sync(r.Context(), body.Label, body.Addresses)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, acc)
}

// 按 ID 返回账户。
func (s *Server) getAccount(w http.ResponseWriter, r *http.Request) {
	acc, err := s.Accounts.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, acc)
}

// 返回一个账户的资产组合。
func (s *Server) assets(w http.ResponseWriter, r *http.Request) {
	portfolio, err := s.Assets.Portfolio(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, portfolio)
}

// 接收转账参数并返回未签名报价。
func (s *Server) prepare(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AccountID string `json:"accountId"`
		Chain     string `json:"chain"`
		Asset     string `json:"asset"`
		From      string `json:"from"`
		To        string `json:"to"`
		Amount    string `json:"amount"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	result, err := s.Transfers.Prepare(r.Context(), service.PrepareInput{
		AccountID: body.AccountID,
		Chain:     domain.ChainID(body.Chain),
		Asset:     body.Asset,
		From:      body.From,
		To:        body.To,
		Amount:    body.Amount,
	})
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// 接收已签名交易，按需广播。
func (s *Server) submit(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AccountID     string `json:"accountId"`
		Chain         string `json:"chain"`
		Asset         string `json:"asset"`
		From          string `json:"from"`
		To            string `json:"to"`
		Amount        string `json:"amount"`
		Fee           string `json:"fee"`
		SignedPayload string `json:"signedPayload"`
		Broadcast     bool   `json:"broadcast"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	rec, err := s.Transfers.Submit(r.Context(), service.SubmitInput{
		AccountID: body.AccountID, Chain: domain.ChainID(body.Chain), Asset: body.Asset,
		From: body.From, To: body.To, Amount: body.Amount, Fee: body.Fee,
		SignedPayload: body.SignedPayload, Broadcast: body.Broadcast,
	})
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, rec)
}

// 返回某账户的转账记录。
func (s *Server) listTransfers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.Transfers.List(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"transfers": rows})
}

// 按 ID 返回一笔转账。
func (s *Server) getTransfer(w http.ResponseWriter, r *http.Request) {
	rec, err := s.Transfers.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

// 广播一笔已签名但未发送的交易。
func (s *Server) broadcast(w http.ResponseWriter, r *http.Request) {
	rec, err := s.Transfers.Broadcast(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

// 向节点刷新一笔交易的状态。
func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	rec, err := s.Transfers.Refresh(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

// 返回五条链的连通情况。
func (s *Server) chainHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"chains": s.Chains.Health(r.Context())})
}

// 返回某条链允许执行的探针。
func (s *Server) probes(w http.ResponseWriter, r *http.Request) {
	adapter, err := s.Chains.Get(domain.ChainID(r.PathValue("chain")))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"probes": adapter.Probes()})
}

// 执行一个白名单探针。
func (s *Server) runProbe(w http.ResponseWriter, r *http.Request) {
	adapter, err := s.Chains.Get(domain.ChainID(r.PathValue("chain")))
	if err != nil {
		writeErr(w, err)
		return
	}
	var body struct {
		Address string `json:"address"`
	}
	if r.Body != nil {
		_ = readJSON(r, &body)
	}
	result, err := adapter.RunProbe(r.Context(), r.PathValue("name"), map[string]string{"address": body.Address})
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"result": result})
}

// 为账户开一张卡。
func (s *Server) createCard(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Kind   string `json:"kind"`
		Holder string `json:"holder"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	issued, err := s.Cards.Create(r.Context(), r.PathValue("id"), body.Kind, body.Holder)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, issued)
}

// 列出账户下的卡。
func (s *Server) listCards(w http.ResponseWriter, r *http.Request) {
	cards, err := s.Cards.List(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"cards": cards})
}

// 返回一张卡的展示信息。
func (s *Server) getCard(w http.ResponseWriter, r *http.Request) {
	card, err := s.Cards.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, card)
}

// 解密并返回完整卡号。
func (s *Server) revealCard(w http.ResponseWriter, r *http.Request) {
	secret, err := s.Cards.Reveal(r.Context(), r.PathValue("id"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, secret)
}

// 处理充值请求。
func (s *Server) topup(w http.ResponseWriter, r *http.Request) { s.move(w, r, s.Cards.Topup) }

// 处理消费请求。
func (s *Server) spend(w http.ResponseWriter, r *http.Request) { s.move(w, r, s.Cards.Spend) }

// 处理提现请求。
func (s *Server) withdraw(w http.ResponseWriter, r *http.Request) {
	s.move(w, r, s.Cards.Withdraw)
}

// 读取金额和幂等键，再调用具体的动账方法。
func (s *Server) move(w http.ResponseWriter, r *http.Request, fn func(context.Context, string, service.MoveInput) (domain.MoveResult, error)) {
	var body service.MoveInput
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	if body.IdempotencyKey == "" {
		body.IdempotencyKey = r.Header.Get("Idempotency-Key")
	}
	result, err := fn(r.Context(), r.PathValue("id"), body)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// 推进卡的状态。
func (s *Server) transition(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string `json:"action"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	card, err := s.Cards.Transition(r.Context(), r.PathValue("id"), body.Action)
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, card)
}

// 按月份返回账单。
func (s *Server) bill(w http.ResponseWriter, r *http.Request) {
	bill, err := s.Cards.Bill(r.Context(), r.PathValue("id"), r.URL.Query().Get("month"))
	if err != nil {
		writeErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, bill)
}

// 解析请求体，空体和坏 JSON 都返回参数错误。
func readJSON(r *http.Request, dst any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(dst); err != nil {
		return domain.E("INVALID_ARGUMENT", "请求体不是合法 JSON", "")
	}
	return nil
}

// 把结果写成 JSON 响应。
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// 把错误写成统一的 error 对象，并选对应的 HTTP 状态码。
func writeErr(w http.ResponseWriter, err error) {
	de := fault.Explain(err)
	writeJSON(w, fault.Status(err), map[string]any{"error": de})
}

// 给浏览器放开跨域。预检请求直接返回。
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// 记录方法、路径、状态码和耗时。
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, code: 200}
		next.ServeHTTP(sw, r)
		slog.Info("http", "method", r.Method, "path", r.URL.Path, "status", sw.code, "ms", time.Since(start).Milliseconds())
	})
}

type statusWriter struct {
	http.ResponseWriter
	code int
}

// 记下响应状态码，供访问日志使用。
func (w *statusWriter) WriteHeader(code int) {
	w.code = code
	w.ResponseWriter.WriteHeader(code)
}
