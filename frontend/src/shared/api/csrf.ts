interface Token { headerName: string; token: string }
let pending: Promise<Token> | null = null;
let generation = 0;
export function resetCsrf() { pending = null; generation++; }
export async function csrfHeaders(): Promise<Record<string, string>> {
  if (!pending) {
    const current = generation;
    pending = fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }).then(async response => {
      if (!response.ok) throw new Error('Cannot establish a secure session. Please retry.');
      const token = await response.json() as Token;
      if (typeof token.token !== 'string' || token.headerName !== 'X-CSRF-TOKEN') throw new Error('Invalid CSRF response.');
      return token;
    }).catch(error => { if (generation === current) pending = null; throw error; });
  }
  const token = await pending;
  return { [token.headerName]: token.token };
}
