const USER_ID_KEY = "agent-crossing:user-id";

export function getCurrentUserId() {
  if (typeof window === "undefined") {
    return "anonymous";
  }
  const existing = window.localStorage.getItem(USER_ID_KEY);
  if (existing && existing.trim()) {
    return existing;
  }
  const userId = "user-" + crypto.randomUUID();
  window.localStorage.setItem(USER_ID_KEY, userId);
  return userId;
}
