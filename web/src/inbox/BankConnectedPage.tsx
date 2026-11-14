import { useEffect, useRef } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { useFinishBankConnection } from "../api/hooks";
import { useHousehold } from "../households/HouseholdPages";
import { ErrorText, PageHeader, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { ConnectionFeedsForm } from "./ConnectionFeedsForm";

/**
 * Where the bank sends the user back after they agree (or decline) to share their account data.
 * Calls `/finish` with `?ref=<connectionId>` and lets the owner turn the discovered accounts into feeds.
 */
export function BankConnectedPage() {
  const household = useHousehold();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const ref = params.get("ref");
  const bankError = params.get("details") || params.get("error");
  const finish = useFinishBankConnection(household.id);
  const startedRef = useRef<string | null>(null);

  useEffect(() => {
    if (!ref || startedRef.current === ref) return;
    startedRef.current = ref;
    finish.mutate(ref);
  }, [ref]);

  const toInbox = () => navigate(`/h/${household.id}/inbox`);

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Bank connection" />
      <Surface className="max-w-2xl p-5">
        {!ref && (
          <div className="flex flex-col gap-3">
            <p className="text-sm text-destructive">{bankError || "Missing connection reference from the bank."}</p>
            <div>
              <Button type="button" onClick={toInbox}>
                Back to Inbox
              </Button>
            </div>
          </div>
        )}
        {ref && finish.isPending && <p className="text-sm text-muted-foreground">Checking the connection with your bank…</p>}
        {ref && finish.isError && (
          <div className="flex flex-col gap-3">
            <ErrorText error={finish.error} />
            <div className="flex gap-2">
              <Button type="button" variant="outline" onClick={() => finish.mutate(ref)}>
                Try again
              </Button>
              <Button type="button" onClick={toInbox}>
                Back to Inbox
              </Button>
            </div>
          </div>
        )}
        {finish.data && finish.data.status === "active" && (
          <ConnectionFeedsForm connection={finish.data} onDone={toInbox} cancelLabel="Back to Inbox" />
        )}
        {finish.data && finish.data.status !== "active" && (
          <div className="flex flex-col gap-3">
            <p className="text-sm">
              {finish.data.institutionName}: <strong>{finish.data.status}</strong>
              {finish.data.lastError ? ` — ${finish.data.lastError}` : ""}
            </p>
            <div className="flex gap-2">
              {finish.data.status === "pending" && (
                <Button type="button" variant="outline" onClick={() => finish.mutate(ref!)}>
                  Check again
                </Button>
              )}
              <Button type="button" onClick={toInbox}>
                Back to Inbox
              </Button>
            </div>
          </div>
        )}
      </Surface>
    </div>
  );
}
