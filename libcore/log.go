package libcore

// 文件日志 writer 的自实现替代：
// 带大小截断的文件日志 writer，同时接管标准库 log 的输出。

import (
	"bytes"
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
		return
	}
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
	if w.disabled || w.file == nil {
		return len(p), nil
	}
	// 超过上限时保留后半段（从下一个完整行开始），而不是清空整个文件：
	// 50KB 全删的旧策略会丢掉隔夜日志。仍在原 inode 上原地重写，
	// 主进程和 :bg 进程同时持有该文件，Remove/重建会丢另一进程的日志。
	if info, statErr := w.file.Stat(); statErr == nil && info.Size()+int64(len(p)) > w.maxSize {
		keep := w.maxSize / 2
		if buf, readErr := os.ReadFile(w.path); readErr == nil && int64(len(buf)) > keep {
			start := int64(len(buf)) - keep
			if idx := bytes.IndexByte(buf[start:], '\n'); idx >= 0 {
				start += int64(idx) + 1
			}
			_ = w.file.Truncate(0)
			_, _ = w.file.Seek(0, 0)
			_, _ = w.file.Write(buf[start:])
		} else {
			_ = w.file.Truncate(0)
			_, _ = w.file.Seek(0, 0)
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
	_ = w.file.Truncate(0)
	_, _ = w.file.Seek(0, 0)
}
