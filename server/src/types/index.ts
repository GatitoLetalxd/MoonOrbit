export type UserRole = 'admin' | 'member';
export type MembershipStatus = 'pending' | 'active' | 'rejected' | 'left';
export type LocationStatus = 'active' | 'paused' | 'location_disabled' | 'no_signal';
export type NoticeSeverity = 'info' | 'important';

export interface UserRow {
  id: string;
  username: string;
  password_hash: string;
  must_change_password: boolean;
  display_name: string;
  phone: string | null;
  avatar_path: string | null;
  avatar_version: number;
  accepted_terms_version: string | null;
  accepted_terms_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

export interface SessionRow {
  id: string;
  user_id: string;
  token_hash: string;
  device_name: string | null;
  app_version_code: number | null;
  created_at: Date;
  last_used_at: Date;
  expires_at: Date;
  revoked_at: Date | null;
}

export interface GroupRow {
  id: string;
  name: string;
  invite_code: string;
  invite_code_expires_at: Date;
  created_by: string | null;
  created_at: Date;
  updated_at: Date;
}

export interface GroupMemberRow {
  group_id: string;
  user_id: string;
  role: UserRole;
  status: MembershipStatus;
  joined_at: Date;
  decided_at: Date | null;
  decided_by: string | null;
  updated_at: Date;
}

export interface UserStateRow {
  user_id: string;
  latitude: number | null;
  longitude: number | null;
  accuracy_m: number | null;
  low_accuracy: boolean;
  battery_level: number | null;
  is_charging: boolean | null;
  recorded_at: Date | null;
  received_at: Date | null;
  last_seen_at: Date | null;
  status: LocationStatus;
  status_changed_at: Date;
}

export interface AppReleaseRow {
  version_code: number;
  version_name: string;
  apk_url: string;
  apk_sha256: string;
  size_bytes: number;
  release_notes: string | null;
  is_min_supported: boolean;
  published_at: Date;
}

export interface NoticeRow {
  id: string;
  title: string;
  message: string;
  severity: NoticeSeverity;
  dismissible: boolean;
  starts_at: Date;
  ends_at: Date | null;
  created_at: Date;
}

export interface TermsVersionRow {
  version: string;
  url: string;
  text_hash: string;
  effective_at: Date;
  created_at: Date;
}
