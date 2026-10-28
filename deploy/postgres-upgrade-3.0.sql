
-- 2단계 인증 (OTP 앱)
ALTER TABLE tb_app_user ADD COLUMN IF NOT EXISTS totp_secret varchar(64);
ALTER TABLE tb_app_user ADD COLUMN IF NOT EXISTS totp_enabled boolean DEFAULT false NOT NULL;
ALTER TABLE tb_app_user ADD COLUMN IF NOT EXISTS recovery_codes varchar(1000);
