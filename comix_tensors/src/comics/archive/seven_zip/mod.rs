mod error;

use self::error::*;
use super::{ArchiveFile, ComicContainer, ComicFilesStream};

use std::ffi::CString;
use std::mem;
use std::os::unix::io::RawFd;
use std::ptr;
use std::slice;
use std::thread::current as current_thread;

use libc::{dup, fclose, fdopen};

use lzma_sdk_sys as lzma;
use lzma_sdk_sys::{size_t, Byte, UInt16, UInt32, SZ};

use tokio::prelude::*;

const INPUT_BUF_SIZE: size_t = (1 << 18);

type SevenZipResult<T> = Result<T, SevenZipError>;

///Open archive from file descriptor
fn open_archive(fd: RawFd) -> SevenZipResult<lzma::CFileInStream> {
    debug!("Trying to open 7z archive using FD: {}", fd);

    let mode = CString::new("rb").expect("Can't create the new Cstring while opening 7z archive");

    let file = unsafe { fdopen(fd, mode.as_ptr()) };

    if file.is_null() {
        let txt = format!("Can't open 7z archive with FD: {}", fd);

        error!("{}", txt);
        return Err(txt.into());
    }

    Ok(file.into())
}

///Start point of the 7z archive logic
#[derive(Debug, Copy, Clone)]
pub struct SevenZipArchive(RawFd);

impl SevenZipArchive {
    pub fn new(fd: RawFd) -> Self {
        debug!("File descriptor of the 7z archive: {}", fd);
        SevenZipArchive(fd)
    }

    ///Open archive in the [Future]
    fn open(&self) -> impl Future<Item = OpenedArchive, Error = SevenZipError> {
        let fd = self.0;

        future::lazy(move || {
            let fd = unsafe { dup(fd) };

            debug!("Trying to init 7z ArchiveData");
            let mut archive_data = ArchiveData::new(fd)?;
            archive_data.init(INPUT_BUF_SIZE)?;
            debug!("7z ArchiveData inited");

            Ok(OpenedArchive::from(archive_data))
        })
    }

    ///Stream over all files in the 7z archive
    fn stream_files(&self) -> impl Stream<Item = ArchiveFile, Error = SevenZipError> {
        self.open().map(stream::iter_result).flatten_stream()
    }
}

impl ComicContainer for SevenZipArchive {
    fn files(&self) -> ComicFilesStream {
        Box::new(self.stream_files().from_err())
    }
}

///Data associated with [OpenedArchive]
#[derive(Debug, Clone)]
struct ArchiveData {
    //should put it to the Box. Without it after every move of the structure in the memory. Lzma library will lose reference
    //look  self.look_stream.realStream = &self.archive_stream.vt
    archive_stream: Box<lzma::CFileInStream>,
    alloc_imp: lzma::ISzAlloc,
    alloc_temp_imp: lzma::ISzAlloc,
    look_stream: lzma::CLookToRead2,
    db: lzma::CSzArEx,
}

impl ArchiveData {
    ///Open archive from file descriptor
    fn new(fd: RawFd) -> SevenZipResult<Self> {
        Ok(ArchiveData {
            archive_stream: Box::new(open_archive(fd)?),
            alloc_imp: lzma::ISzAlloc::g_alloc(),
            alloc_temp_imp: lzma::ISzAlloc::g_alloc(),
            look_stream: lzma::CLookToRead2::default(),
            db: lzma::CSzArEx::default(),
        })
    }

    ///Init data
    fn init(&mut self, buf_size: size_t) -> SevenZipResult<()> {
        unsafe {
            lzma::FileInStream_CreateVTable(&mut *self.archive_stream);
            lzma::LookToRead2_CreateVTable(&mut self.look_stream, 0);
        }

        self.look_stream.buf = self.alloc_imp.alloc(buf_size) as *mut Byte;

        if self.look_stream.buf.is_null() {
            error!("7z archive can't allocate buffer");
            return Err(SevenZipError::Native(SZ::SZ_ERROR_MEM));
        }

        self.look_stream.bufSize = buf_size;
        //the reference will dogling without the Box
        self.look_stream.realStream = &self.archive_stream.vt;
        self.look_stream.init();

        {
            let res = unsafe {
                lzma::CrcGenerateTable();
                lzma::SzArEx_Init(&mut self.db);

                lzma::SzArEx_Open(
                    &mut self.db,
                    &mut self.look_stream.vt,
                    &mut self.alloc_imp,
                    &mut self.alloc_temp_imp,
                )
            };

            if !res.is_ok() {
                error!("7z archive can't open extractor. Result: {:?}", res);
                return Err(SevenZipError::Native(res));
            }
        }

        Ok(())
    }
}

impl Drop for ArchiveData {
    fn drop(&mut self) {
        unsafe {
            let res = fclose(self.archive_stream.file.file);

            lzma::SzArEx_Free(&mut self.db, &mut self.alloc_imp);
            self.alloc_imp.free(self.look_stream.buf as _);

            if res != 0 {
                panic!("Can't close 7z archive file. Error: {}", res);
            }
        }
    }
}

///Opened archive, used to iterate over all files in it
/// Use [IntoIterator] to iterate
#[derive(Debug, Clone)]
struct OpenedArchive {
    archive_data: ArchiveData,

    iter_readed_count: UInt32,

    temp_size: size_t,
    temp: *mut UInt16,
    //if you need cache, use these 3 variables.
    //if you use external function, you can make these variable as static.
    block_index: UInt32, // it can have any value before first call (if outBuffer = 0)
    out_buffer: *mut Byte, //it must be 0 before first call for each new archive.
    out_buffer_size: size_t, //it can have any value before first call (if outBuffer = 0)
}

unsafe impl Send for OpenedArchive {}

impl IntoIterator for OpenedArchive {
    type Item = SevenZipResult<ArchiveFile>;
    type IntoIter = ArchiveIterator;

    fn into_iter(self) -> Self::IntoIter {
        ArchiveIterator {
            archive: self,
            current_pos: 0,
        }
    }
}

impl From<ArchiveData> for OpenedArchive {
    fn from(archive_data: ArchiveData) -> Self {
        OpenedArchive {
            archive_data,
            iter_readed_count: 0,
            temp_size: 0,
            temp: ptr::null_mut(),
            block_index: 0xFFFFFFFF,
            out_buffer: 0 as *mut _,
            out_buffer_size: 0,
        }
    }
}

impl OpenedArchive {
    ///Return count of files in the archive
    fn files_count(&self) -> usize {
        self.archive_data.db.NumFiles as usize
    }
}

///Iterator over files in the archive
struct ArchiveIterator {
    archive: OpenedArchive,
    current_pos: usize,
}

impl ArchiveIterator {
    ///Check is file by [pos] directory or not
    fn is_dir(&self, pos: UInt32) -> bool {
        self.archive.archive_data.db.is_dir(pos)
    }

    ///Read and return file content by position [pos]
    fn read_file<'a>(&mut self, pos: UInt32) -> SevenZipResult<&'a mut [u8]> {
        debug!(
            "Trying to get 7z file content by position: {}. Thread: {:?}",
            pos,
            current_thread().name()
        );

        let mut offset = 0 as size_t;
        let mut out_size_processed = 0 as size_t;

        let res = unsafe {
            lzma::SzArEx_Extract(
                &self.archive.archive_data.db,
                &mut self.archive.archive_data.look_stream.vt,
                pos,
                &mut self.archive.block_index,
                &mut self.archive.out_buffer,
                &mut self.archive.out_buffer_size,
                &mut offset,
                &mut out_size_processed,
                &mut self.archive.archive_data.alloc_imp,
                &mut self.archive.archive_data.alloc_temp_imp,
            )
        };

        if !res.is_ok() {
            let txt = format!(
                "Can't get 7z file content by position {}. Result {:?}",
                pos, res
            );

            error!("{}", txt);
            return Err(txt.into());
        }

        Ok(unsafe {
            slice::from_raw_parts_mut(self.archive.out_buffer.add(offset), out_size_processed)
        })
    }

    ///Read and return file name by position [pos]
    pub fn read_file_name(&mut self, pos: size_t) -> SevenZipResult<String> {
        debug!(
            "Trying to get 7z file name by position: {}. Thread: {:?}",
            pos,
            current_thread().name()
        );

        let file_name = unsafe {
            let len =
                lzma::SzArEx_GetFileNameUtf16(&self.archive.archive_data.db, pos, ptr::null_mut());

            //If there is no enough space in the buffer. Allocate a new one with proper size
            if len > self.archive.temp_size {
                lzma::SzFree(ptr::null_mut(), self.archive.temp as *mut _);

                self.archive.temp_size = len;

                self.archive.temp = lzma::SzAlloc(
                    ptr::null_mut(),
                    self.archive.temp_size * mem::size_of_val(&self.archive.temp),
                ) as *mut _;

                //temp = (UInt16 *)SzAlloc(NULL, tempSize * sizeof(temp[0]));
                if self.archive.temp.is_null() {
                    let txt = format!("Can't get 7z file name by position {}", pos);

                    error!("{}", txt);
                    return Err(txt.into());
                }
            }

            lzma::SzArEx_GetFileNameUtf16(&self.archive.archive_data.db, pos, self.archive.temp);

            slice::from_raw_parts(self.archive.temp, len - 1)
        };

        Ok(String::from_utf16(file_name)?)
    }
}

impl Iterator for ArchiveIterator {
    type Item = SevenZipResult<ArchiveFile>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.current_pos == self.archive.files_count() {
            return None;
        }

        let is_dir = self.is_dir(self.current_pos as _);

        let file_name = match self.read_file_name(self.current_pos) {
            Err(e) => return Some(Err(e)),
            Ok(file_name) => file_name,
        };

        let file_content = match self.read_file(self.current_pos as _).map(|c| c.to_owned()) {
            Err(e) => return Some(Err(e)),
            Ok(_) if is_dir => None,
            Ok(content) => Some(content),
        };

        let file = ArchiveFile {
            pos: self.current_pos,
            name: file_name,
            is_dir,
            content: file_content,
        };

        debug!(
            "7z archive file '{}' extracted. Thread: {:?}",
            file.name,
            current_thread().name()
        );

        self.current_pos += 1;

        Some(Ok(file))
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        (0, Some(self.archive.files_count()))
    }
}

impl ExactSizeIterator for ArchiveIterator {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::comics::archive::tests::open_archive_fd;
    use crate::comics::magic::{resolve_file_magic_type, MagicType};

    use std::fs::File;
    use std::os::unix::io::FromRawFd;

    #[test]
    #[cfg(target_family = "unix")]
    fn test_stream_cb7_archive() {
        let fd = open_7z_fd();

        let mut file_count = 0u32;

        SevenZipArchive::new(fd)
            .stream_files()
            .wait()
            .for_each(|file| {
                let file = file.unwrap();

                assert_eq!(
                    file.content.is_none(),
                    file.is_dir,
                    "If it's a file it should contain content. Otherwise it should be empty"
                );

                assert_eq!(
                    file.is_dir,
                    file.name == "image_folder",
                    "Wrong dir detection {}",
                    file.name
                );

                file_count += 1;
            });

        assert_eq!(
            file_count, 11,
            "Wrong number of 7z archive files. Count {}",
            file_count
        );
    }

    #[test]
    #[cfg(target_family = "unix")]
    fn test_guess_cb7_magic_type() {
        let fd = open_7z_fd();
        let mut file = unsafe { File::from_raw_fd(fd) };

        let res = resolve_file_magic_type(&mut file).unwrap();
        assert_eq!(res, MagicType::SZ);
    }

    #[cfg(target_family = "unix")]
    fn open_7z_fd() -> RawFd {
        open_archive_fd(&["7z", "comics_test.cb7"])
    }
}
