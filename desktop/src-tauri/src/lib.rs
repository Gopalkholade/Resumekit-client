use std::{env, fs, io::Write, path::PathBuf};

#[tauri::command]
fn get_api_key(app: tauri::AppHandle) -> Result<String, String> {
    if let Some(settings) = read_api_settings(&app)? {
        return Ok(settings["api_key"]
            .as_str()
            .ok_or_else(|| "The saved API key is invalid.".to_string())?
            .to_owned());
    }

    if let Ok(key) = env::var("RESUMEKIT_API_KEY") {
        let key = key.trim();
        if !key.is_empty() {
            return Ok(key.to_owned());
        }
    }

    Err("Configure the API URL and token in Connection settings first.".to_string())
}

#[tauri::command]
fn load_api_settings(app: tauri::AppHandle) -> Result<Option<String>, String> {
    read_api_settings(&app).map(|settings| {
        settings.map(|value| {
            serde_json::json!({
                "api_url": value["api_url"],
                "api_key": value["api_key"]
            })
            .to_string()
        })
    })
}

#[tauri::command]
fn save_api_settings(app: tauri::AppHandle, settings_json: String) -> Result<(), String> {
    let settings_json = zeroize::Zeroizing::new(settings_json);
    validate_api_settings_json(&settings_json)?;
    let encrypted = protect_profile(settings_json.as_bytes())?;
    let path = api_settings_path(&app)?;
    let parent = path
        .parent()
        .ok_or_else(|| "Could not determine API settings storage directory.".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Could not create API settings storage directory: {error}"))?;

    let mut temporary = tempfile::NamedTempFile::new_in(parent)
        .map_err(|error| format!("Could not create temporary encrypted API settings: {error}"))?;
    temporary
        .write_all(&encrypted)
        .map_err(|error| format!("Could not write encrypted API settings: {error}"))?;
    temporary
        .as_file()
        .sync_all()
        .map_err(|error| format!("Could not flush encrypted API settings: {error}"))?;
    temporary.persist(&path).map_err(|error| {
        format!(
            "Could not securely save encrypted API settings: {}",
            error.error
        )
    })?;
    Ok(())
}

fn read_api_settings(app: &tauri::AppHandle) -> Result<Option<serde_json::Value>, String> {
    let path = api_settings_path(app)?;
    let encrypted = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("Could not read encrypted API settings: {error}")),
    };
    if encrypted.len() > MAX_API_SETTINGS_BYTES + 1024 {
        return Err("Encrypted API settings exceed the size limit.".to_string());
    }
    let plaintext = unprotect_profile(&encrypted)?;
    let settings = zeroize::Zeroizing::new(
        String::from_utf8(plaintext.to_vec())
            .map_err(|_| "Saved API settings are not valid UTF-8.".to_string())?,
    );
    validate_api_settings_json(&settings)?;
    let value = serde_json::from_str(&settings)
        .map_err(|error| format!("Saved API settings are not valid JSON: {error}"))?;
    Ok(Some(value))
}

fn validate_api_settings_json(settings_json: &str) -> Result<(), String> {
    if settings_json.len() > MAX_API_SETTINGS_BYTES {
        return Err("API settings exceed the size limit.".to_string());
    }
    let value: serde_json::Value = serde_json::from_str(settings_json)
        .map_err(|error| format!("API settings are not valid JSON: {error}"))?;
    if value.get("version").and_then(serde_json::Value::as_u64) != Some(1) {
        return Err("API settings have an unsupported version.".to_string());
    }
    let api_url = value
        .get("api_url")
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| "API settings must include an API URL.".to_string())?;
    let api_key = value
        .get("api_key")
        .and_then(serde_json::Value::as_str)
        .ok_or_else(|| "API settings must include an API key.".to_string())?;

    let valid_space_url = api_url
        .strip_prefix("https://")
        .and_then(|host_and_path| host_and_path.split('/').next())
        .is_some_and(|host| {
            host.to_ascii_lowercase().ends_with(".hf.space")
                && !host
                    .chars()
                    .any(|character| matches!(character, '@' | '?' | '#' | ':'))
        });
    if !valid_space_url {
        return Err("Use a Hugging Face Space HTTPS URL.".to_string());
    }
    if api_key.trim().len() < 16 || api_key.len() > 4096 {
        return Err("The API key must be between 16 and 4096 characters.".to_string());
    }
    Ok(())
}

const MAX_API_SETTINGS_BYTES: usize = 8192;

fn api_settings_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    use tauri::Manager;

    app.path()
        .app_local_data_dir()
        .map(|directory| directory.join("api-settings.dpapi"))
        .map_err(|error| format!("Could not locate API settings storage: {error}"))
}

#[tauri::command]
fn load_saved_profile(app: tauri::AppHandle) -> Result<Option<String>, String> {
    let path = profile_path(&app)?;
    let encrypted = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(format!(
                "Could not read the encrypted ResumeKit profile: {error}"
            ));
        }
    };
    if encrypted.len() > MAX_ENCRYPTED_PROFILE_BYTES {
        return Err("The encrypted profile exceeds the 10 MiB safety limit.".to_string());
    }

    let plaintext = unprotect_profile(&encrypted)?;
    let profile = zeroize::Zeroizing::new(
        String::from_utf8(plaintext.to_vec())
            .map_err(|_| "The saved profile is not valid UTF-8.".to_string())?,
    );
    validate_profile_json(&profile)?;
    Ok(Some(profile.to_string()))
}

#[tauri::command]
fn save_profile(app: tauri::AppHandle, profile_json: String) -> Result<(), String> {
    let profile_json = zeroize::Zeroizing::new(profile_json);
    if profile_json.len() > MAX_PROFILE_BYTES {
        return Err("The profile exceeds the 10 MiB safety limit.".to_string());
    }
    validate_profile_json(&profile_json)?;

    let encrypted = protect_profile(profile_json.as_bytes())?;
    if encrypted.len() > MAX_ENCRYPTED_PROFILE_BYTES {
        return Err("The encrypted profile exceeds the 10 MiB safety limit.".to_string());
    }

    let path = profile_path(&app)?;
    let parent = path
        .parent()
        .ok_or_else(|| "Could not determine the profile storage directory.".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Could not create the profile storage directory: {error}"))?;

    let mut temporary = tempfile::NamedTempFile::new_in(parent)
        .map_err(|error| format!("Could not create a temporary encrypted profile: {error}"))?;
    temporary
        .write_all(&encrypted)
        .map_err(|error| format!("Could not write the encrypted profile: {error}"))?;
    temporary
        .as_file()
        .sync_all()
        .map_err(|error| format!("Could not flush the encrypted profile: {error}"))?;
    temporary.persist(&path).map_err(|error| {
        format!(
            "Could not securely save the encrypted profile: {}",
            error.error
        )
    })?;
    Ok(())
}

#[tauri::command]
fn delete_saved_profile(app: tauri::AppHandle) -> Result<(), String> {
    let path = profile_path(&app)?;
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("Could not delete the saved profile: {error}")),
    }
}

const MAX_PROFILE_BYTES: usize = 10 * 1024 * 1024;
const MAX_ENCRYPTED_PROFILE_BYTES: usize = MAX_PROFILE_BYTES + 1024;

fn profile_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    use tauri::Manager;

    app.path()
        .app_local_data_dir()
        .map(|directory| directory.join("profile.dpapi"))
        .map_err(|error| format!("Could not locate local profile storage: {error}"))
}

fn validate_profile_json(profile_json: &str) -> Result<(), String> {
    let value: serde_json::Value = serde_json::from_str(profile_json)
        .map_err(|error| format!("Profile data is not valid JSON: {error}"))?;
    let version = value.get("version").and_then(serde_json::Value::as_u64);
    if !matches!(version, Some(1) | Some(2))
        || !value
            .get("profile")
            .is_some_and(serde_json::Value::is_object)
    {
        return Err("Profile data has an unsupported or invalid format.".to_string());
    }
    Ok(())
}

#[cfg(windows)]
fn protect_profile(plaintext: &[u8]) -> Result<Vec<u8>, String> {
    use std::{ffi::c_void, ptr};

    const MAGIC: &[u8] = b"RKDPAPI1";

    #[repr(C)]
    struct DataBlob {
        size: u32,
        data: *mut u8,
    }

    #[link(name = "Crypt32")]
    unsafe extern "system" {
        fn CryptProtectData(
            input: *const DataBlob,
            description: *const u16,
            entropy: *const DataBlob,
            reserved: *mut c_void,
            prompt: *mut c_void,
            flags: u32,
            output: *mut DataBlob,
        ) -> i32;
    }
    #[link(name = "Kernel32")]
    unsafe extern "system" {
        fn LocalFree(memory: *mut c_void) -> *mut c_void;
    }

    let input_size = u32::try_from(plaintext.len())
        .map_err(|_| "Profile data is too large to encrypt.".to_string())?;
    let input = DataBlob {
        size: input_size,
        data: plaintext.as_ptr().cast_mut(),
    };
    let mut output = DataBlob {
        size: 0,
        data: ptr::null_mut(),
    };
    let succeeded = unsafe {
        CryptProtectData(
            &input,
            ptr::null(),
            ptr::null(),
            ptr::null_mut(),
            ptr::null_mut(),
            0x1,
            &mut output,
        )
    };
    if succeeded == 0 {
        return Err(format!(
            "Windows could not encrypt the profile: {}",
            std::io::Error::last_os_error()
        ));
    }

    let result = unsafe { std::slice::from_raw_parts(output.data, output.size as usize) };
    let mut encrypted = Vec::with_capacity(MAGIC.len() + result.len());
    encrypted.extend_from_slice(MAGIC);
    encrypted.extend_from_slice(result);
    unsafe {
        LocalFree(output.data.cast());
    }
    Ok(encrypted)
}

#[cfg(not(windows))]
fn protect_profile(_plaintext: &[u8]) -> Result<Vec<u8>, String> {
    Err("Encrypted profile storage is currently supported on Windows only.".to_string())
}

#[cfg(windows)]
fn unprotect_profile(encrypted: &[u8]) -> Result<zeroize::Zeroizing<Vec<u8>>, String> {
    use std::{ffi::c_void, ptr};
    use zeroize::Zeroizing;

    const MAGIC: &[u8] = b"RKDPAPI1";
    if !encrypted.starts_with(MAGIC) || encrypted.len() <= MAGIC.len() {
        return Err("The saved profile has an unknown or invalid encryption format.".to_string());
    }
    let ciphertext = &encrypted[MAGIC.len()..];
    let input_size = u32::try_from(ciphertext.len())
        .map_err(|_| "The encrypted profile is too large to decrypt.".to_string())?;

    #[repr(C)]
    struct DataBlob {
        size: u32,
        data: *mut u8,
    }

    #[link(name = "Crypt32")]
    unsafe extern "system" {
        fn CryptUnprotectData(
            input: *const DataBlob,
            description: *mut *mut u16,
            entropy: *const DataBlob,
            reserved: *mut c_void,
            prompt: *mut c_void,
            flags: u32,
            output: *mut DataBlob,
        ) -> i32;
    }
    #[link(name = "Kernel32")]
    unsafe extern "system" {
        fn LocalFree(memory: *mut c_void) -> *mut c_void;
    }

    let input = DataBlob {
        size: input_size,
        data: ciphertext.as_ptr().cast_mut(),
    };
    let mut output = DataBlob {
        size: 0,
        data: ptr::null_mut(),
    };
    let succeeded = unsafe {
        CryptUnprotectData(
            &input,
            ptr::null_mut(),
            ptr::null(),
            ptr::null_mut(),
            ptr::null_mut(),
            0x1,
            &mut output,
        )
    };
    if succeeded == 0 {
        return Err(format!(
            "Windows could not decrypt the saved profile for this user: {}",
            std::io::Error::last_os_error()
        ));
    }

    let output_slice = unsafe { std::slice::from_raw_parts_mut(output.data, output.size as usize) };
    let plaintext = Zeroizing::new(output_slice.to_vec());
    unsafe {
        for byte in output_slice {
            ptr::write_volatile(byte, 0);
        }
        std::sync::atomic::compiler_fence(std::sync::atomic::Ordering::SeqCst);
        LocalFree(output.data.cast());
    }
    Ok(plaintext)
}

#[cfg(not(windows))]
fn unprotect_profile(_encrypted: &[u8]) -> Result<Vec<u8>, String> {
    Err("Encrypted profile storage is currently supported on Windows only.".to_string())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .invoke_handler(tauri::generate_handler![
            get_api_key,
            load_api_settings,
            save_api_settings,
            load_saved_profile,
            save_profile,
            delete_saved_profile
        ])
        .run(tauri::generate_context!())
        .expect("error while running ResumeKit desktop");
}

#[cfg(test)]
mod tests {
    use super::{validate_api_settings_json, validate_profile_json};

    #[test]
    fn profile_envelope_must_be_versioned_object() {
        assert!(validate_profile_json(r#"{"version":1,"profile":{}}"#).is_ok());
        assert!(validate_profile_json(r#"{"version":2,"profile":{}}"#).is_ok());
        assert!(validate_profile_json(r#"{"version":3,"profile":{}}"#).is_err());
        assert!(validate_profile_json(r#"{"version":1,"profile":[]}"#).is_err());
        assert!(validate_profile_json("invalid").is_err());
    }

    #[test]
    fn api_settings_require_space_url_and_a_token() {
        assert!(validate_api_settings_json(
            r#"{"version":1,"api_url":"https://my-space.hf.space","api_key":"0123456789abcdef"}"#
        )
        .is_ok());
        assert!(validate_api_settings_json(
            r#"{"version":1,"api_url":"http://example.com","api_key":"0123456789abcdef"}"#
        )
        .is_err());
        assert!(validate_api_settings_json(
            r#"{"version":1,"api_url":"https://example.com","api_key":"0123456789abcdef"}"#
        )
        .is_err());
        assert!(validate_api_settings_json(
            r#"{"version":1,"api_url":"https://user@my-space.hf.space","api_key":"0123456789abcdef"}"#
        )
        .is_err());
    }

    #[cfg(windows)]
    #[test]
    fn profile_encryption_is_bound_to_the_current_windows_user() {
        let profile = br#"{"version":1,"profile":{"full_name":"Test"}}"#;
        let encrypted = super::protect_profile(profile).expect("profile should encrypt");
        assert!(encrypted.starts_with(b"RKDPAPI1"));
        let decrypted =
            super::unprotect_profile(&encrypted).expect("profile should decrypt for this user");
        assert_eq!(decrypted.as_slice(), profile);
    }
}
