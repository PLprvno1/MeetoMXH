-- Preserve Android case-insensitive literal contains search, scoped by RLS.
CREATE OR REPLACE FUNCTION private.get_messages(p_conversation_id uuid,p_before_seq bigint,p_after_seq bigint,p_limit integer,p_query text DEFAULT NULL) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR (p_before_seq IS NOT NULL AND p_after_seq IS NOT NULL) OR p_before_seq<=0 OR p_after_seq<0
 OR (p_query IS NOT NULL AND length(btrim(p_query)) NOT BETWEEN 2 AND 100) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message_cursor'; END IF;
 RETURN jsonb_build_object('high_water',(SELECT next_seq FROM public.conversations WHERE id=p_conversation_id),
 'conversation_version',(SELECT version FROM public.conversations WHERE id=p_conversation_id),
 'items',coalesce((SELECT jsonb_agg(private.c_message(q.id) ORDER BY CASE WHEN p_after_seq IS NOT NULL THEN q.seq END ASC,q.seq DESC) FROM (
  SELECT m.id,m.seq FROM public.messages m WHERE m.conversation_id=p_conversation_id AND private.can_read_message(m.id)
  AND (p_before_seq IS NULL OR m.seq<p_before_seq) AND (p_after_seq IS NULL OR m.seq>p_after_seq)
  AND (p_query IS NULL OR (m.state='sent' AND m.kind='text' AND strpos(lower(m.body),lower(btrim(p_query)))>0))
  ORDER BY CASE WHEN p_after_seq IS NOT NULL THEN m.seq END ASC,m.seq DESC LIMIT p_limit) q),'[]'::jsonb));
END $$;
CREATE FUNCTION private.search_messages(p_conversation_id uuid,p_query text,p_before_seq bigint,p_limit integer) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF p_query IS NULL OR length(btrim(p_query)) NOT BETWEEN 2 AND 100 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search_query'; END IF;
 RETURN private.get_messages(p_conversation_id,p_before_seq,NULL,p_limit,p_query);
END $$;
GRANT EXECUTE ON FUNCTION private.search_messages(uuid,text,bigint,integer) TO authenticated;
CREATE OR REPLACE FUNCTION public.search_messages(p_conversation_id uuid,p_query text,p_before_seq bigint DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_messages(p_conversation_id,p_query,p_before_seq,p_limit) $$;
NOTIFY pgrst,'reload schema';