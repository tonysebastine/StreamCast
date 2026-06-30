import os
import base64
import sys

def main():
    print("Starting keystore decoding process...")
    raw_data = os.environ.get("KEYSTORE_BASE64", "").strip()
    if not raw_data:
        print("Error: KEYSTORE_BASE64 environment variable is empty or not set.")
        sys.exit(1)

    print(f"Raw KEYSTORE_BASE64 length: {len(raw_data)} characters.")

    # Remove any Data URI prefixes if accidentally copied (e.g. data:application/octet-stream;base64,...)
    if "," in raw_data:
        parts = raw_data.split(",", 1)
        header = parts[0].lower()
        if "data:" in header or "base64" in header:
            print("Detected Data URI scheme prefix. Stripping header prefix...")
            raw_data = parts[1]

    # Remove all whitespace, newlines, and carriage returns
    cleaned_data = "".join(raw_data.split())
    print(f"Cleaned base64 length (whitespace removed): {len(cleaned_data)} characters.")

    # Convert URL-safe base64 characters to standard base64 characters
    standardized_data = cleaned_data.replace("-", "+").replace("_", "/")
    if standardized_data != cleaned_data:
        print("Standardized URL-safe base64 characters (- and _ converted to + and /).")

    # Correct and re-verify padding
    standardized_data = standardized_data.rstrip("=")
    missing_padding = len(standardized_data) % 4
    if missing_padding == 1:
        standardized_data += "==="
    elif missing_padding == 2:
        standardized_data += "=="
    elif missing_padding == 3:
        standardized_data += "="
    
    print(f"Final standardized base64 length with padding: {len(standardized_data)} characters.")

    try:
        decoded_bytes = base64.b64decode(standardized_data)
        print(f"Base64 successfully decoded. Decoded binary size: {len(decoded_bytes)} bytes.")
        
        # Ensure the destination directory exists
        os.makedirs("app", exist_ok=True)
        dest_path = os.path.join("app", "release.jks")
        
        with open(dest_path, "wb") as f:
            f.write(decoded_bytes)
            
        print(f"Successfully wrote decoded keystore to {dest_path}")
    except Exception as e:
        print(f"CRITICAL ERROR: Failed to decode base64 or write keystore: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
