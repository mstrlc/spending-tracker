import { Navigate, Outlet, useLocation } from "react-router";
import { useMe } from "../api/hooks";
import { ErrorText, Loading } from "../ui";

/** Renders child routes only with a session; otherwise sends the user to log in. */
export function RequireAuth() {
  const me = useMe();
  const location = useLocation();
  if (me.isPending) return <Loading />;
  if (me.isError) return <ErrorText error={me.error} />;
  if (!me.data) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}
