// 运行配置。MODE 决定用沙箱还是真节点，NETWORK 决定测试网还是主网，两者独立。
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"aegis/internal/domain"
)

type Config struct {
	Addr    string
	Mode    string
	Network string
	DBPath  string
	KeyPath string
	CardKey string
	RPCs    map[domain.ChainID]string
}

// 读取环境变量，整理监听地址、模式、网络、数据库路径和各链节点。
func Load() (Config, error) {
	mode := getenv("MODE", "live")
	if mode != "sandbox" && mode != "live" {
		return Config{}, fmt.Errorf("MODE 只能是 sandbox 或 live")
	}
	network := getenv("NETWORK", "testnet")
	if network != "testnet" && network != "mainnet" {
		return Config{}, fmt.Errorf("NETWORK 只能是 testnet 或 mainnet")
	}
	dataDir := getenv("DATA_DIR", defaultDataDir())
	dbPath := getenv("DB_PATH", filepath.Join(dataDir, "aegis.db"))
	rpcs := defaultRPCs(network)
	if v := os.Getenv("ETH_RPC"); strings.TrimSpace(v) != "" {
		rpcs[domain.Ethereum] = strings.TrimSpace(v)
	}
	if v := os.Getenv("BNB_RPC"); strings.TrimSpace(v) != "" {
		rpcs[domain.BNB] = strings.TrimSpace(v)
	}
	if v := os.Getenv("SOL_RPC"); strings.TrimSpace(v) != "" {
		rpcs[domain.Solana] = strings.TrimSpace(v)
	}
	if v := os.Getenv("TRON_RPC"); strings.TrimSpace(v) != "" {
		rpcs[domain.Tron] = strings.TrimSpace(v)
	}
	if v := os.Getenv("BTC_API"); strings.TrimSpace(v) != "" {
		rpcs[domain.Bitcoin] = strings.TrimSpace(v)
	}
	return Config{
		Addr:    getenv("ADDR", ":8080"),
		Mode:    mode,
		Network: network,
		DBPath:  dbPath,
		KeyPath: filepath.Join(dataDir, "card.key"),
		CardKey: os.Getenv("CARD_KEY"),
		RPCs:    rpcs,
	}, nil
}

// 没设 DATA_DIR 时，Go 和 Java 都用仓库里的 server/data。从 server 或 server-java 启动也能找到。
func defaultDataDir() string {
	dir, err := os.Getwd()
	if err != nil {
		return "data"
	}
	for {
		if isDir(filepath.Join(dir, "server")) && isDir(filepath.Join(dir, "server-java")) {
			return filepath.Join(dir, "server", "data")
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "data"
		}
		dir = parent
	}
}

func isDir(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// 读取环境变量，空白时用默认值。
func getenv(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

// 公共节点只给学习用。生产环境应换成自己的节点，并用环境变量覆盖。
func defaultRPCs(network string) map[domain.ChainID]string {
	if network == "mainnet" {
		return map[domain.ChainID]string{
			domain.Ethereum: "https://ethereum.publicnode.com",
			domain.BNB:      "https://bsc-dataseed.binance.org",
			domain.Solana:   "https://api.mainnet-beta.solana.com",
			domain.Tron:     "https://api.trongrid.io",
			domain.Bitcoin:  "https://mempool.space/api",
		}
	}
	return map[domain.ChainID]string{
		domain.Ethereum: "https://ethereum-sepolia.publicnode.com",
		domain.BNB:      "https://bsc-testnet.publicnode.com",
		domain.Solana:   "https://api.devnet.solana.com",
		domain.Tron:     "https://api.shasta.trongrid.io",
		domain.Bitcoin:  "https://mempool.space/testnet/api",
	}
}
