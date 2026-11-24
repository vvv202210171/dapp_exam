package config

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestDefaultDataDirSharedWithJava(t *testing.T) {
	dir := defaultDataDir()
	if !strings.HasSuffix(filepath.Clean(dir), filepath.Join("server", "data")) {
		t.Fatalf("data dir = %s", dir)
	}
}
