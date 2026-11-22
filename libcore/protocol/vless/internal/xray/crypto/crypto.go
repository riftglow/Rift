package crypto

import (
	"crypto/rand"
	"math/big"
)

func RandBetween(from int64, to int64) int64 {
	if from == to {
		return from
	}
	if from > to {
		from, to = to, from
	}
	// crypto/rand 失败极罕见，但一旦失败 bigInt 为 nil，直接取
	// bigInt.Int64() 会 panic。失败时回落到下限，不 panic。
	bigInt, err := rand.Int(rand.Reader, big.NewInt(to-from))
	if err != nil || bigInt == nil {
		return from
	}
	return from + bigInt.Int64()
}
