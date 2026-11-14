// 随机 UUID v4。幂等键和业务主键都用它，不依赖数据库自增。
package platform

import (
	"crypto/rand"
	"fmt"
)

// 生成一个 UUID v4 字符串。
func NewID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
