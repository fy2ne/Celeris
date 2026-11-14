pub const CAP_NODELAY: u32 = 1 << 0;
pub const CAP_LOWAT: u32 = 1 << 1;
pub const CAP_BBR: u32 = 1 << 2;
pub const CAP_QUICKACK: u32 = 1 << 3;
pub const CAP_TCP_INFO: u32 = 1 << 4;
pub const CAP_SNDBUF_CLAMP: u32 = 1 << 5;

#[derive(Clone, Copy, Debug, Default)]
pub struct TcpStats {
    pub srtt_us: u32,
    pub rttvar_us: u32,
    pub unacked: u32,
    pub retrans: u32,
    pub snd_cwnd: u32,
}

pub fn tune_socket(fd: i64, lowat_bytes: u32) -> u32 {
    if fd < 0 {
        return 0;
    }

    #[cfg(target_os = "linux")]
    {
        return tune_linux(fd as libc::c_int, lowat_bytes);
    }

    #[cfg(all(unix, not(target_os = "linux")))]
    {
        let _ = lowat_bytes;
        return tune_unix_fallback(fd as libc::c_int);
    }

    #[cfg(target_os = "windows")]
    {
        return tune_windows(fd as usize, lowat_bytes);
    }

    #[cfg(not(any(unix, target_os = "windows")))]
    {
        let _ = lowat_bytes;
        0
    }
}

pub fn refresh_quickack(fd: i64) {
    if fd < 0 {
        return;
    }
    #[cfg(target_os = "linux")]
    {
        let one: libc::c_int = 1;
        unsafe {
            set_sock_int(fd as libc::c_int, libc::IPPROTO_TCP, libc::TCP_QUICKACK, &one);
        }
    }
}

pub fn update_lowat(fd: i64, lowat_bytes: u32) -> bool {
    if fd < 0 {
        return false;
    }
    #[cfg(target_os = "linux")]
    {
        let lowat: libc::c_int = lowat_bytes as libc::c_int;
        return unsafe { set_sock_int(fd as libc::c_int, libc::IPPROTO_TCP, libc::TCP_NOTSENT_LOWAT, &lowat) == 0 };
    }
    #[cfg(target_os = "windows")]
    {
        use windows_sys::Win32::Networking::WinSock::{setsockopt, SOL_SOCKET, SO_SNDBUF};
        let sndbuf: i32 = (lowat_bytes.max(16384) * 2).min(65536) as i32;
        return unsafe {
            setsockopt(
                fd as usize,
                SOL_SOCKET as i32,
                SO_SNDBUF as i32,
                (&sndbuf as *const i32).cast::<u8>(),
                std::mem::size_of::<i32>() as i32,
            ) == 0
        };
    }
    #[cfg(not(any(target_os = "linux", target_os = "windows")))]
    {
        let _ = lowat_bytes;
        false
    }
}

pub fn poll_tcp_stats(fd: i64) -> Option<TcpStats> {
    if fd < 0 {
        return None;
    }

    #[cfg(target_os = "linux")]
    {
        let mut info: libc::tcp_info = unsafe { std::mem::zeroed() };
        let mut len = std::mem::size_of::<libc::tcp_info>() as libc::socklen_t;
        let rc = unsafe {
            libc::getsockopt(
                fd as libc::c_int,
                libc::IPPROTO_TCP,
                libc::TCP_INFO,
                (&mut info as *mut libc::tcp_info).cast::<libc::c_void>(),
                &mut len,
            )
        };
        if rc == 0 {
            return Some(TcpStats {
                srtt_us: info.tcpi_rtt,
                rttvar_us: info.tcpi_rttvar,
                unacked: info.tcpi_unacked,
                retrans: info.tcpi_retrans,
                snd_cwnd: info.tcpi_snd_cwnd,
            });
        }
        None
    }

    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

#[cfg(target_os = "linux")]
fn tune_linux(fd: libc::c_int, lowat_bytes: u32) -> u32 {
    let mut caps = 0u32;
    let one: libc::c_int = 1;
    let lowat: libc::c_int = lowat_bytes as libc::c_int;

    unsafe {
        if set_sock_int(fd, libc::IPPROTO_TCP, libc::TCP_NODELAY, &one) == 0 {
            caps |= CAP_NODELAY;
        }
        if set_sock_int(fd, libc::IPPROTO_TCP, libc::TCP_QUICKACK, &one) == 0 {
            caps |= CAP_QUICKACK;
        }
        if set_sock_int(fd, libc::IPPROTO_TCP, libc::TCP_NOTSENT_LOWAT, &lowat) == 0 {
            caps |= CAP_LOWAT;
        }
        // pterodactyl and unprivileged containers often block tcp_congestion with eperm
        let bbr = b"bbr";
        let rc = libc::setsockopt(
            fd,
            libc::IPPROTO_TCP,
            libc::TCP_CONGESTION,
            bbr.as_ptr().cast::<libc::c_void>(),
            bbr.len() as libc::socklen_t,
        );
        if rc == 0 {
            caps |= CAP_BBR;
        }
        let mut probe: libc::tcp_info = std::mem::zeroed();
        let mut len = std::mem::size_of::<libc::tcp_info>() as libc::socklen_t;
        if libc::getsockopt(
            fd,
            libc::IPPROTO_TCP,
            libc::TCP_INFO,
            (&mut probe as *mut libc::tcp_info).cast::<libc::c_void>(),
            &mut len,
        ) == 0
        {
            caps |= CAP_TCP_INFO;
        }
    }
    caps
}

#[cfg(target_os = "linux")]
unsafe fn set_sock_int(fd: libc::c_int, level: libc::c_int, opt: libc::c_int, val: &libc::c_int) -> libc::c_int {
    libc::setsockopt(
        fd,
        level,
        opt,
        (val as *const libc::c_int).cast::<libc::c_void>(),
        std::mem::size_of::<libc::c_int>() as libc::socklen_t,
    )
}

#[cfg(all(unix, not(target_os = "linux")))]
fn tune_unix_fallback(fd: libc::c_int) -> u32 {
    let one: libc::c_int = 1;
    let rc = unsafe {
        libc::setsockopt(
            fd,
            libc::IPPROTO_TCP,
            libc::TCP_NODELAY,
            (&one as *const libc::c_int).cast::<libc::c_void>(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };
    if rc == 0 { CAP_NODELAY } else { 0 }
}

#[cfg(target_os = "windows")]
fn tune_windows(sock: usize, lowat_bytes: u32) -> u32 {
    use windows_sys::Win32::Networking::WinSock::{
        setsockopt, IPPROTO_TCP, SOL_SOCKET, SO_SNDBUF, TCP_NODELAY,
    };

    let mut caps = 0u32;
    let one: i32 = 1;
    // winsock has no tcp_notsent_lowat; capping sndbuf limits kernel queue bloat
    let sndbuf: i32 = (lowat_bytes.max(16384) * 2).min(65536) as i32;

    unsafe {
        if setsockopt(
            sock,
            IPPROTO_TCP as i32,
            TCP_NODELAY as i32,
            (&one as *const i32).cast::<u8>(),
            std::mem::size_of::<i32>() as i32,
        ) == 0
        {
            caps |= CAP_NODELAY;
        }
        if setsockopt(
            sock,
            SOL_SOCKET as i32,
            SO_SNDBUF as i32,
            (&sndbuf as *const i32).cast::<u8>(),
            std::mem::size_of::<i32>() as i32,
        ) == 0
        {
            caps |= CAP_SNDBUF_CLAMP;
        }
    }
    caps
}
