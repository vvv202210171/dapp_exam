// 进程入口。助记词和私钥不进这个进程：这里只组装数据库、卡号密钥和链适配器。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"aegis/internal/chain"
	"aegis/internal/config"
	"aegis/internal/httpapi"
	"aegis/internal/platform"
	"aegis/internal/platform/httpx"
	"aegis/internal/service"
	"aegis/internal/store"
)

// 启动 API：打开数据库、加载卡密钥、按配置接上链适配器，收到中断信号后关闭。
func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, nil)))
	cfg, err := config.Load()
	if err != nil {
		slog.Error("config", "err", err)
		os.Exit(1)
	}
	db, err := store.Open(cfg.DBPath)
	if err != nil {
		slog.Error("db", "err", err)
		os.Exit(1)
	}
	defer db.Close()
	key, err := platform.LoadMasterKey(cfg.KeyPath, cfg.CardKey)
	if err != nil {
		slog.Error("card key", "err", err)
		os.Exit(1)
	}
	if cfg.CardKey == "" {
		slog.Warn("card encryption key is a local file; production would use a KMS", "path", cfg.KeyPath)
	}
	client := httpx.New()
	reg := chain.NewRegistry(cfg, client)
	accounts := service.NewAccount(db, cfg.Network)
	prices := service.NewPricer(cfg.Mode, client)
	api := httpapi.New(
		accounts,
		service.NewAsset(db, reg, prices),
		service.NewTransfer(db, reg, prices, cfg.Network, cfg.Mode),
		service.NewCard(db, key),
		reg,
	)
	// 超时是为了挡住慢客户端占住连接。写超时要盖住一次链上报价。
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      40 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	go func() {
		slog.Info("aegis listening", "addr", cfg.Addr, "mode", cfg.Mode, "network", cfg.Network)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server", "err", err)
			os.Exit(1)
		}
	}()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdown)
}
