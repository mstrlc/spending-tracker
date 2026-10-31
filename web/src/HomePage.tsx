import { useQuery } from "@tanstack/react-query";
import { api } from "./api/client";

export function HomePage() {
  const health = useQuery({
    queryKey: ["health"],
    queryFn: async () => {
      const { data, error } = await api.GET("/api/health");
      if (error) throw new Error(`Database ${error.database}`);
      return data;
    },
  });

  return (
    <main>
      <h1>Spending Tracker</h1>
      {health.isPending && <p>Checking API…</p>}
      {health.isError && <p role="alert">API unavailable: {health.error.message}</p>}
      {health.data && <p>API {health.data.status}, database {health.data.database}</p>}
    </main>
  );
}
