package libcore

import (
	"archive/zip"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/ulikunitz/xz"
	"github.com/sagernet/sing/common"
        E "github.com/sagernet/sing/common/exceptions"
)

// Unxz decompresses an xz archive to path atomically: the output goes
// to a unique tmp file in the same directory and is renamed into
// place, so a crash mid-decompress cannot leave a half-written file
// at the destination (the old code wrote straight to path).
func Unxz(archive string, path string) error {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()
	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}
	tmp := uniqueTmpName(path)
	o, err := os.Create(tmp)
	if err != nil {
		return err
	}
	_, err = io.Copy(o, r)
	closeErr := o.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func Unzip(archive string, path string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	err = os.MkdirAll(path, os.ModePerm)
	if err != nil {
		return err
	}

	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)
		// Zip Slip: 拒绝写出目标目录外的路径。
		if !strings.HasPrefix(filepath.Clean(filePath), filepath.Clean(path)+string(filepath.Separator)) && filepath.Clean(filePath) != filepath.Clean(path) {
			return E.New("zip entry outside target dir: " + file.Name)
		}

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(filePath, os.ModePerm)
			if err != nil {
				return err
			}
			continue
		}

		if err := os.MkdirAll(filepath.Dir(filePath), os.ModePerm); err != nil {
			return err
		}
		newFile, err := os.Create(filePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			return err
		}

		var errs error
		_, err = io.Copy(newFile, zipFile)
		errs = E.Errors(errs, err)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			return errs
		}
	}

	return nil
}
