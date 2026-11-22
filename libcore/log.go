package libcore

// 文件日志 writer 的自实现替代：
// 带大小截断的文件日志 writer，同时接管标准库 log 的输出。

import (
	"bytes"
	"fmt"
	"log"
	"os"
	"sync"
)

type fileLogWriter struct {
	access   sync.Mutex
	file     *os.File
	path     string
	maxSize  int64
	disabled bool
	// setupLog 打开文件失败时记在这里（Write 不再假装写成功），供排障
	// 时区分“Go 没打开文件”和“文件真的空”。
	openErr error
}

var platformLog = new(fileLogWriter)

// setupLog 初始化日志文件。启动时不清文件：主进程和 :bg 进程会同时持有
// rift.log，且日志要跨重启保留（隔夜排障）；清空只走 Truncate()（手动清日志）。
// 不能先 Remove 再 Open，否则较早启动的进程仍会写入已取消链接的旧 inode，
// 导出时只能看到后启动进程的日志。
func setupLog(maxSizeBytes int, path string, disabled bool) {
	platformLog.access.Lock()
	defer platformLog.access.Unlock()

	platformLog.maxSize = int64(maxSizeBytes)
	platformLog.path = path
	platformLog.disabled = disabled

	flags := os.O_CREATE | os.O_APPEND | os.O_WRONLY
	file, err := os.OpenFile(path, flags, 0644)
	if err != nil {
		log.Println("open log file failed:", err)
		platformLog.openErr = err
		return
	}
	platformLog.openErr = nil
	if platformLog.file != nil {
		_ = platformLog.file.Close()
	}
	platformLog.file = file

	// 标准库 log 也写入同一文件
	log.SetOutput(platformLog)
	log.SetFlags(log.LstdFlags | log.LUTC)
}

func (w *fileLogWriter) Write(p []byte) (n int, err error) {
	w.access.Lock()
	defer w.access.Unlock()
	if w.disabled {
		return len(p), nil
	}
	if w.file == nil {
		// 文件没打开（setupLog 失败）：不再假装写成功，返回打开错误，
		// 调用方才能区分“Go 没打开文件”和“文件真的空”。
		if w.openErr != nil {
			return 0, w.openErr
		}
		return 0, fmt.Errorf("rift.log not opened")
	}
	// 超过上限时保留后半段（从下一个完整行开始），而不是清空整个文件：
	// 50KB 全删的旧策略会丢掉隔夜日志。仍在原 inode 上原地重写，
	// 主进程和 :bg 进程同时持有该文件，Remove/重建会丢另一进程的日志。
	// 已知局限（跨进程截断竞态）：access 是进程内锁，主进程和 :bg 进程
	// 各持一把，截断是读-改-写，两个进程同时截断时后写的会盖掉先写的
	// 一小段日志。截断只在文件满 2MB 时发生、窗口极小，丢的只是几行
	// 日志，不影响排障，接受为已知局限，不上跨进程文件锁。
	if info, statErr := w.file.Stat(); statErr == nil && info.Size()+int64(len(p)) > w.maxSize {
		keep := w.maxSize / 2
		if buf, readErr := os.ReadFile(w.path); readErr == nil && int64(len(buf)) > keep {
			start := int64(len(buf)) - keep
			// 后半段里没换行（单行超 1MB 的极端情况）：不截断，宁可文件
			// 暂时超限，也不要从半行开始留一句残行；下一次写入再试。
			if idx := bytes.IndexByte(buf[start:], '\n'); idx >= 0 {
				start += int64(idx) + 1
			} else {
				return w.file.Write(p)
			}
			// 截断写回的 I/O 错误不再用 _ = 吞掉：短写/失败会静默丢日志。
			// 这里不能用 log 包报（log 的输出就是本 writer，会递归），
			// 直接写 stderr。
			if err := w.file.Truncate(0); err != nil {
				fmt.Fprintf(os.Stderr, "rift.log truncate failed: %v\n", err)
			} else if _, err := w.file.Seek(0, 0); err != nil {
				fmt.Fprintf(os.Stderr, "rift.log seek failed: %v\n", err)
			} else if _, err := w.file.Write(buf[start:]); err != nil {
				fmt.Fprintf(os.Stderr, "rift.log rewrite failed: %v\n", err)
			}
		} else {
			// ReadFile 失败才全清（之前是任何读不到/不够长都全清，会丢日志），
			// 并记原因；buf 太短（单次写入超大）时不全清，靠最后的 Write
			// 把文件写超上限，下一次写入再截断。
			if readErr != nil {
				fmt.Fprintf(os.Stderr, "rift.log read failed, clearing: %v\n", readErr)
				if err := w.file.Truncate(0); err != nil {
					fmt.Fprintf(os.Stderr, "rift.log truncate failed: %v\n", err)
				} else if _, err := w.file.Seek(0, 0); err != nil {
					fmt.Fprintf(os.Stderr, "rift.log seek failed: %v\n", err)
				}
			}
		}
	}
	return w.file.Write(p)
}

// Truncate 清空日志文件（对应原 LogWriter.Truncate，供 RiftLogClear 调用）。
func (w *fileLogWriter) Truncate() {
	w.access.Lock()
	defer w.access.Unlock()
	if w.file == nil {
		return
	}
	if err := w.file.Truncate(0); err != nil {
		fmt.Fprintf(os.Stderr, "rift.log truncate failed: %v\n", err)
		return
	}
	if _, err := w.file.Seek(0, 0); err != nil {
		fmt.Fprintf(os.Stderr, "rift.log seek failed: %v\n", err)
	}
}
